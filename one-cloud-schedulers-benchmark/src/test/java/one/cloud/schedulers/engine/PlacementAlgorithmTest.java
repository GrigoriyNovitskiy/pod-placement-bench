package one.cloud.schedulers.engine;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.schedulers.dataset.PlacementDataset;
import one.cloud.schedulers.dataset.PlacementDatasetProtos;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Header;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Kind;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Operation;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Record;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Resource;
import one.cloud.schedulers.dataset.PlacementDatasetProtos.Stream;
import one.cloud.schedulers.model.Placement;
import one.cloud.schedulers.model.PlacementModel;
import one.cloud.schedulers.model.PlacementRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlacementAlgorithmTest {
    @TempDir
    Path temporary;

    @Test
    void requestsKeepTheirOrderAndOwnTheirStorage() {
        final int[] resources = {3, 1, 2};
        final var request = new PlacementRequest(resources);
        resources[0] = 99;
        assertEquals(3, request.size());
        assertEquals(3, request.resource(0));
        assertEquals(1, request.resource(1));
        assertEquals(2, request.resource(2));
        for (final var invalid : List.of(new int[0], new int[]{0}, new int[]{-1}, new int[]{1, 2, 1})) {
            assertThrows(IllegalArgumentException.class, () -> new PlacementRequest(invalid));
        }
    }

    @Test
    void plansBorrowBuffersAndCanSnapshotThem() {
        final int[] offsets = {0, 2};
        final int[] devices = {4, 5};
        final var plan = new Placement(3, 1, 2, offsets, devices);
        final var copy = plan.copy();
        assertSame(offsets, plan.diskOffsets());
        assertSame(devices, plan.devices());
        assertNotSame(offsets, copy.diskOffsets());
        assertNotSame(devices, copy.devices());
        offsets[1] = 0;
        devices[0] = 99;
        assertArrayEquals(new int[]{0, 2}, copy.diskOffsets());
        assertArrayEquals(new int[]{4, 5}, copy.devices());
        assertEquals(3, copy.resource());
        assertEquals(1, copy.host());
        assertEquals(2, copy.numa());
        new Placement(3, 1, 0, new int[]{0}, new int[0]);
    }

    @Test
    void plansRejectInvalidStructure() {
        for (final var invalid : List.of(new int[0], new int[]{1}, new int[]{0, 2}, new int[]{0, 1, 0, 1})) {
            assertThrows(IllegalArgumentException.class, () -> new Placement(3, 1, 0, invalid, new int[]{4}));
        }
        for (final var ids : List.of(new int[]{0, 1, 0}, new int[]{3, 0, 0}, new int[]{3, 1, -1})) {
            assertThrows(IllegalArgumentException.class, () -> new Placement(ids[0], ids[1], ids[2], new int[]{0}, new int[0]));
        }
        assertThrows(IllegalArgumentException.class, () -> new Placement(3, 1, 0, new int[]{0, 1}, new int[]{0}));
        assertThrows(NullPointerException.class, () -> new Placement(3, 1, 0, null, new int[0]));
        assertThrows(NullPointerException.class, () -> new Placement(3, 1, 0, new int[]{0}, null));
    }

    @Test
    void modelSharesStaticTablesWithoutRetainingReplayState() throws Exception {
        final var dataset = dataset();
        final var model = PlacementModel.from(dataset);
        assertEquals(dataset.id, model.id);
        assertEquals(dataset.hostCount, model.hostCount);
        assertEquals(dataset.dimensionCount, model.dimensionCount);
        assertEquals(dataset.extentBytes, model.extentBytes);
        assertSame(dataset.alloc, model.alloc);
        assertSame(dataset.constraints, model.constraints);
        assertSame(dataset.allocations, model.allocations);
        assertSame(dataset.rules, model.rules);
        assertSame(dataset.candidates, model.candidates);
        for (final var field : PlacementModel.class.getDeclaredFields()) {
            assertFalse(field.getType() == PlacementDataset.class || field.getType() == PlacementDataset.Placements.class
                    || field.getType() == PlacementDataset.Operations.class);
        }
    }

    @Test
    void baseClassUsesARepeatableRandomSource() throws Exception {
        final var context = (PlacementContext) Proxy.newProxyInstance(PlacementContext.class.getClassLoader(),
                new Class<?>[]{PlacementContext.class}, (proxy, method, arguments) -> {
                    throw new AssertionError(method.getName());
                });
        assertArrayEquals(new SplittableRandom(42).ints(16).toArray(), draw(context, 42));
        assertThrows(NullPointerException.class, () -> draw(null, 42));
    }

    private static int[] draw(PlacementContext expectedContext, long seed) throws Exception {
        final int[] values = new int[16];
        try (final var algorithm = new PlacementAlgorithm(expectedContext, seed) {
            @Override
            public void schedule(PlacementRequest request) {
                assertSame(expectedContext, context);
                final int start = (request.resource(0) - 1) * 8;
                for (int index = start; index < start + 8; index++) {
                    values[index] = random.nextInt();
                }
            }
        }) {
            algorithm.schedule(new PlacementRequest[]{new PlacementRequest(1), new PlacementRequest(2)});
            final var plan = new Placement(2, 1, 0, new int[]{0}, new int[0]);
            algorithm.placed(plan);
            algorithm.removed(plan);
        }
        return values;
    }

    private PlacementDataset dataset() throws Exception {
        final var snapshot = temporary.resolve("snapshot.pb");
        try (final var output = Files.newOutputStream(snapshot)) {
            for (final var record : List.of(
                    Record.newBuilder().setHeader(Header.newBuilder().setVersion(2).setDatasetId(42).setStream(Stream.SNAPSHOT)
                            .setHostCount(1).setResourceCount(2).setDimensionCount(1).setExtentBytes(1024)
                            .setAllocCount(1).setAllocValueCount(1).setConstraintCount(2).setCandidateCount(1).setPlacementCount(1)).build(),
                    Record.newBuilder().setAlloc(Alloc.newBuilder().setId(1).addType(1).addAmount(8)).build(),
                    Record.newBuilder().setConstraints(Constraints.newBuilder().setId(1).setKind(Kind.HOST)).build(),
                    Record.newBuilder().setConstraints(Constraints.newBuilder().setId(2).setKind(Kind.POD)).build(),
                    Record.newBuilder().setResource(Resource.newBuilder().setId(1).setAlloc(1).setConstraints(1)).build(),
                    Record.newBuilder().setResource(Resource.newBuilder().setId(2).setAlloc(1).setConstraints(2)).build(),
                    Record.newBuilder().setPlacement(PlacementDatasetProtos.Placement.newBuilder().setResource(2).setHost(1).setAlloc(1)).build())) {
                record.writeDelimitedTo(output);
            }
        }
        final var trace = temporary.resolve("operations.pb");
        try (final var output = Files.newOutputStream(trace)) {
            Record.newBuilder().setHeader(Header.newBuilder().setVersion(2).setDatasetId(42).setStream(Stream.OPERATIONS)
                    .setResourceCount(2).setOperationCount(1).setSyntheticOperations(true)).build().writeDelimitedTo(output);
            Record.newBuilder().setOperation(Operation.newBuilder().setTime(1).setResource(2).setGeneration(1).setCreate(true))
                    .build().writeDelimitedTo(output);
        }
        return PlacementDataset.load(snapshot, trace);
    }
}
