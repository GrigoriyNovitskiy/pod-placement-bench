package one.cloud.benchmark.engine;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.PlacementDatasetProtos;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Operation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Stream;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulingContractTest {
    @TempDir
    Path temporary;

    @Test
    void requestsKeepTheirOrderAndOwnTheirStorage() {
        final int[] resources = {3, 1, 2};
        final var request = new Request(resources);
        resources[0] = 99;
        assertEquals(3, request.size());
        assertEquals(3, request.resource(0));
        assertEquals(1, request.resource(1));
        assertEquals(2, request.resource(2));
        for (final var invalid : List.of(new int[0], new int[]{0}, new int[]{-1}, new int[]{1, 2, 1})) {
            assertThrows(IllegalArgumentException.class, () -> new Request(invalid));
        }
    }

    @Test
    void plansBorrowBuffersAndCanSnapshotThem() {
        final int[] offsets = {0, 2};
        final int[] devices = {4, 5};
        final var plan = new Assignment(3, 1, 2, offsets, devices);
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
        new Assignment(3, 1, 0, new int[]{0}, new int[0]);
    }

    @Test
    void plansRejectInvalidStructure() {
        for (final var invalid : List.of(new int[0], new int[]{1}, new int[]{0, 2}, new int[]{0, 1, 0, 1})) {
            assertThrows(IllegalArgumentException.class, () -> new Assignment(3, 1, 0, invalid, new int[]{4}));
        }
        for (final var ids : List.of(new int[]{0, 1, 0}, new int[]{3, 0, 0}, new int[]{3, 1, -1})) {
            assertThrows(IllegalArgumentException.class, () -> new Assignment(ids[0], ids[1], ids[2], new int[]{0}, new int[0]));
        }
        assertThrows(IllegalArgumentException.class, () -> new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{0}));
        assertThrows(NullPointerException.class, () -> new Assignment(3, 1, 0, null, new int[0]));
        assertThrows(NullPointerException.class, () -> new Assignment(3, 1, 0, new int[]{0}, null));
    }

    @Test
    void modelSharesStaticTablesWithoutRetainingReplayState() throws Exception {
        final var dataset = dataset();
        final var model = ClusterSpec.from(dataset);
        assertEquals(ClusterSpec.GpuMode.DEVICES_20, model.gpuMode);
        assertEquals(dataset.id, model.id);
        assertEquals(dataset.hostCount, model.hostCount);
        assertEquals(dataset.dimensionCount, model.dimensionCount);
        assertEquals(dataset.extentBytes, model.extentBytes);
        assertSame(dataset.alloc, model.alloc);
        assertSame(dataset.constraints, model.constraints);
        assertSame(dataset.allocations, model.allocations);
        assertSame(dataset.rules, model.rules);
        assertSame(dataset.candidates, model.candidates);
        for (final var field : ClusterSpec.class.getDeclaredFields()) {
            assertFalse(field.getType() == Dataset.class || field.getType() == Dataset.Placements.class
                    || field.getType() == Dataset.Operations.class);
        }
    }

    @Test
    void totalFitLimitSupportsCpuOnlyModels() throws Exception {
        final var model = ClusterSpec.from(dataset());
        final var request = new Request(2);
        final var catalogue = new Request[]{request};
        for (final boolean weighted : new boolean[]{false, true}) {
            final var state = new Engine(model);
            try (final var algorithm = weighted ? FitCount.weighted(state, 42, catalogue, 10, 1, 4, null)
                    : new FitCount(state, 42, catalogue, 10, 1, 4, null)) {
                state.schedule(algorithm, request);
                assertEquals(1, state.placement(2).host());
            }
        }
    }

    @Test
    void baseClassKeepsContextAndDispatchesRequestsInOrder() throws Exception {
        final var context = (SchedulingContext) Proxy.newProxyInstance(SchedulingContext.class.getClassLoader(),
                new Class<?>[]{SchedulingContext.class}, (proxy, method, arguments) -> {
                    throw new AssertionError(method.getName());
                });
        final var first = new Request(1);
        final var second = new Request(2);
        assertEquals(List.of(first, second), dispatch(context, first, second));
        assertThrows(NullPointerException.class, () -> dispatch(null, first, second));
    }

    private static List<Request> dispatch(SchedulingContext expectedContext, Request... requests) throws Exception {
        final var scheduled = new ArrayList<Request>();
        try (final var algorithm = new SchedulingAlgorithm(expectedContext) {
            @Override
            public void schedule(Request request) {
                assertSame(expectedContext, context);
                scheduled.add(request);
            }
        }) {
            algorithm.schedule(requests);
            algorithm.capacityChanged(1);
            assertEquals(-1, algorithm.plannerCalls());
            assertEquals(Map.of(), algorithm.parameters());
            assertEquals(Map.of(), algorithm.counters());
        }
        return scheduled;
    }

    private Dataset dataset() throws Exception {
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
        return Dataset.load(snapshot, trace);
    }
}
