package one.cloud.benchmark.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import one.cloud.benchmark.dataset.PlacementDataset;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Candidates;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;
import one.cloud.benchmark.model.PlacementModel;

public final class TestModel {
    private TestModel() {
    }

    public static PlacementModel model(Record... records) throws IOException {
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
        final var path = Files.createTempFile("placement-model-", ".pb");
        try {
            try (final var output = Files.newOutputStream(path)) {
                Record.newBuilder().setHeader(header).build().writeDelimitedTo(output);
                for (final var record : ordered) {
                    record.writeDelimitedTo(output);
                }
            }
            return PlacementModel.from(PlacementDataset.load(path));
        } finally {
            Files.deleteIfExists(path);
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
