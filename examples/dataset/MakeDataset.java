import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Operation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Stream;

public final class MakeDataset {
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: MakeDataset OUTPUT_DIRECTORY");
        }
        final var directory = Path.of(arguments[0]);
        if (Files.exists(directory.resolve("snapshot.pb")) || Files.exists(directory.resolve("operations.pb"))) {
            throw new IOException("Dataset files already exist in " + directory);
        }
        Files.createDirectories(directory);

        final var snapshotHeader = Header.newBuilder()
                .setVersion(2).setDatasetId(42).setStream(Stream.SNAPSHOT)
                .setResourceCount(4).setHostCount(2)
                .setAllocCount(2).setConstraintCount(2)
                .setCandidateCount(1).setDimensionCount(20)
                .setExtentBytes(64L * 1024 * 1024).setAllocValueCount(4)
                .build();
        write(directory.resolve("snapshot.pb"), snapshotHeader, List.of(
                allocation(1, 8, 16384),
                allocation(2, 2, 2048),
                Record.newBuilder().setConstraints(Constraints.newBuilder()
                        .setId(1).setKind(Kind.HOST).setCpuPower(1)).build(),
                Record.newBuilder().setConstraints(Constraints.newBuilder()
                        .setId(2).setKind(Kind.POD)).build(),
                resource(1, 1, 1),
                resource(2, 1, 1),
                resource(3, 2, 2),
                resource(4, 2, 2)));

        final var operationsHeader = Header.newBuilder()
                .setVersion(2).setDatasetId(42).setStream(Stream.OPERATIONS)
                .setResourceCount(4).setOperationCount(4)
                .setSyntheticOperations(true).setReplayFromSnapshot(false)
                .build();
        write(directory.resolve("operations.pb"), operationsHeader, List.of(
                operation(1, 3, true),
                operation(2, 4, true),
                operation(3, 3, false),
                operation(4, 4, false)));
        System.out.println(directory.toAbsolutePath());
    }

    private static Record allocation(int id, double cpu, double memoryMiB) {
        return Record.newBuilder().setAlloc(Alloc.newBuilder().setId(id)
                .addType(ResourceType.VCORES_VALUE).addAmount(cpu)
                .addType(ResourceType.MEM_MIB_VALUE).addAmount(memoryMiB)).build();
    }

    private static Record resource(int id, int allocation, int constraints) {
        return Record.newBuilder().setResource(Resource.newBuilder()
                .setId(id).setAlloc(allocation).setConstraints(constraints)).build();
    }

    private static Record operation(long time, int resource, boolean create) {
        return Record.newBuilder().setOperation(Operation.newBuilder()
                .setTime(time).setResource(resource).setGeneration(1).setCreate(create)).build();
    }

    private static void write(Path path, Header header, List<Record> records) throws IOException {
        try (final var output = Files.newOutputStream(path)) {
            Record.newBuilder().setHeader(header).build().writeDelimitedTo(output);
            for (final var record : records) {
                record.writeDelimitedTo(output);
            }
        }
    }
}
