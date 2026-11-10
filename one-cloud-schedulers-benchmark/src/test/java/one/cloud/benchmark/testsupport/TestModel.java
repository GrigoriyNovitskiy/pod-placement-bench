package one.cloud.benchmark.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Candidates;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Operation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Stream;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;
import one.cloud.benchmark.model.ClusterSpec;

public final class TestModel {
    private TestModel() {
    }

    public static ClusterSpec model(Record... records) throws IOException {
        return model(ClusterSpec.GpuMode.AGGREGATE, records);
    }

    public static ClusterSpec model(ClusterSpec.GpuMode gpuMode, Record... records) throws IOException {
        final var path = Files.createTempFile("placement-model-", ".pb");
        try {
            writeModel(path, records);
            return ClusterSpec.from(Dataset.load(path), gpuMode);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    public static Dataset trace(Record[] records, int... events) throws IOException {
        return trace(records, true, false, events);
    }

    public static Dataset trace(Record[] records, boolean synthetic, boolean fromSnapshot, int... events) throws IOException {
        final var directory = Files.createTempDirectory("placement-trace-");
        try {
            writeTrace(directory, records, synthetic, fromSnapshot, events);
            return Dataset.load(directory.resolve("snapshot.pb"), directory.resolve("operations.pb"));
        } finally {
            Files.deleteIfExists(directory.resolve("operations.pb"));
            Files.deleteIfExists(directory.resolve("snapshot.pb"));
            Files.deleteIfExists(directory);
        }
    }

    public static void writeTrace(Path directory, Record[] records, int... events) throws IOException {
        writeTrace(directory, records, true, false, events);
    }

    private static void writeTrace(Path directory, Record[] records, boolean synthetic, boolean fromSnapshot, int... events) throws IOException {
        Files.createDirectories(directory);
        writeModel(directory.resolve("snapshot.pb"), records);
        int resourceCount = 0;
        for (final var record : records) {
            if (record.hasResource()) {
                resourceCount++;
            }
        }
        final var generations = new int[resourceCount + 1];
        try (final var output = Files.newOutputStream(directory.resolve("operations.pb"))) {
            Record.newBuilder().setHeader(Header.newBuilder().setVersion(2).setDatasetId(42)
                    .setResourceCount(resourceCount).setStream(Stream.OPERATIONS).setOperationCount(events.length)
                    .setSyntheticOperations(synthetic).setReplayFromSnapshot(fromSnapshot))
                    .build().writeDelimitedTo(output);
            for (int index = 0; index < events.length; index++) {
                final int resource = Math.abs(events[index]);
                if (resource <= 0 || resource > resourceCount) {
                    throw new IllegalArgumentException("Invalid fixture resource");
                }
                if (events[index] > 0) {
                    generations[resource]++;
                }
                Record.newBuilder().setOperation(Operation.newBuilder().setTime(index + 1L).setResource(resource)
                        .setGeneration(Math.max(1, generations[resource])).setCreate(events[index] > 0))
                        .build().writeDelimitedTo(output);
            }
        }
    }

    private static void writeModel(Path path, Record[] records) throws IOException {
        final var header = Header.newBuilder().setVersion(2).setDatasetId(42)
                .setExtentBytes(1024 * 1024).setDimensionCount(20).setCandidateCount(1);
        final var kinds = new HashMap<Integer, Kind>();
        for (final var record : records) {
            switch (record.getPayloadCase()) {
                case ALLOC -> {
                    header.setAllocCount(header.getAllocCount() + 1);
                    header.setAllocValueCount(header.getAllocValueCount() + record.getAlloc().getTypeCount());
                }
                case CONSTRAINTS -> {
                    final var rule = record.getConstraints();
                    kinds.put(rule.getId(), rule.getKind());
                    header.setConstraintCount(header.getConstraintCount() + 1);
                    header.setLimitCount(header.getLimitCount() + rule.getLimitsCount());
                    header.setLinkCount(header.getLinkCount() + rule.getLinksCount());
                    header.setDomainCount(header.getDomainCount() + rule.getDomainsCount());
                    header.setDiskPlanCount(header.getDiskPlanCount() + rule.getDisksCount());
                }
                case CANDIDATES -> {
                    header.setCandidateCount(Math.max(header.getCandidateCount(), record.getCandidates().getId()));
                    header.setCandidateHostCount(header.getCandidateHostCount() + record.getCandidates().getHostCount());
                }
                case RESOURCE -> header.setResourceCount(header.getResourceCount() + 1);
                default -> throw new IllegalArgumentException("Unexpected fixture record");
            }
        }
        for (final var record : records) {
            if (record.hasResource() && kinds.get(record.getResource().getConstraints()) == Kind.HOST) {
                header.setHostCount(header.getHostCount() + 1);
            }
        }
        final var ordered = records.clone();
        Arrays.sort(ordered, Comparator.comparingInt((Record r) -> r.getPayloadCase().getNumber())
                .thenComparingInt(r -> switch (r.getPayloadCase()) {
                    case ALLOC -> r.getAlloc().getId();
                    case CONSTRAINTS -> r.getConstraints().getId();
                    case CANDIDATES -> r.getCandidates().getId();
                    case RESOURCE -> r.getResource().getId();
                    default -> throw new IllegalArgumentException("Unexpected fixture record");
                }));
        try (final var output = Files.newOutputStream(path)) {
            Record.newBuilder().setHeader(header).build().writeDelimitedTo(output);
            for (final var record : ordered) {
                record.writeDelimitedTo(output);
            }
        }
    }

    public static Record alloc(int id, int[] types, double... amounts) {
        final var value = Alloc.newBuilder().setId(id);
        for (final int type : types) {
            value.addType(type);
        }
        for (final double amount : amounts) {
            value.addAmount(amount);
        }
        return Record.newBuilder().setAlloc(value).build();
    }

    public static Record rules(Constraints.Builder value) {
        return Record.newBuilder().setConstraints(value).build();
    }

    public static Record resource(int id, int alloc, int constraints) {
        return Record.newBuilder().setResource(Resource.newBuilder().setId(id).setAlloc(alloc).setConstraints(constraints)).build();
    }

    public static Record candidates(int id, int... hosts) {
        final var value = Candidates.newBuilder().setId(id);
        for (final int host : hosts) {
            value.addHost(host);
        }
        return Record.newBuilder().setCandidates(value).build();
    }
}
