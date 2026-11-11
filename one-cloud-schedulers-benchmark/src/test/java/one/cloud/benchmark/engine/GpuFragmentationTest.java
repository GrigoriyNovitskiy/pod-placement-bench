package one.cloud.benchmark.engine;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuFragmentationTest {
    @Test
    void paperExampleScoresEachDeviceAndRefreshesAfterRemovalWithoutChangingTheEngine() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .5),
                alloc(3, new int[]{11}, .3), alloc(4, new int[]{11}, .7),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2),
                resource(4, 2, 2), resource(5, 4, 2)));
        final var occupied = new Request(2);
        place(state, occupied, plan(2, 1, 1));
        final var scorer = new GreedyPlanner(state).gpuFragmentationScorer(
                new Request[]{new Request(3), new Request(4), new Request(5)});
        assertEquals(2, scorer.applyAsDouble(new Assignment[]{plan(3, 1, 1)}));
        assertEquals(0, scorer.applyAsDouble(new Assignment[]{plan(3, 1, 2)}));
        assertEquals(2, scorer.applyAsDouble(new Assignment[]{plan(3, 1, 1)}));
        assertEquals(10, state.gpuAvailable(1));
        assertEquals(20, state.gpuAvailable(2));
        assertEquals(1.5, state.available(1, 11));
        assertEquals(1, state.revision());
        state.remove(occupied);
        assertEquals(0, scorer.applyAsDouble(new Assignment[]{plan(3, 1, 1)}));
        assertEquals(20, state.gpuAvailable(1));
        assertEquals(2, state.revision());
    }

    @Test
    void strandingUsesTheChangeFromEachHostsOwnBeforeState() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 11}, 8, 1), alloc(2, new int[]{1, 11}, 4, 1),
                alloc(3, new int[]{1}, 4),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 1, 2), resource(4, 3, 2)));
        final var scorer = new GreedyPlanner(state).gpuFragmentationScorer(new Request[]{new Request(3)});
        assertEquals(20, scorer.applyAsDouble(new Assignment[]{plan(4, 1)}));
        assertEquals(0, scorer.applyAsDouble(new Assignment[]{plan(4, 2)}));
        assertEquals(8, state.available(1, 1));
        assertEquals(4, state.available(2, 1));
        assertEquals(0, state.revision());
    }

    @Test
    void wholeMixedTypeAndCpuOnlyProfilesCountUnusableSharesAndPreserveFrequencies() throws Exception {
        final var specification = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11, 17}, 2, 1), alloc(2, new int[]{11}, .5),
                alloc(3, new int[]{11}, 2), alloc(4, new int[]{11}, 1),
                alloc(5, new int[]{11, 17}, .5, 1), alloc(6, new int[]{17}, .5), alloc(7, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 4, 2),
                resource(5, 5, 2), resource(6, 6, 2), resource(7, 7, 2));
        final var capacity = new CapacityLedger(specification);
        capacity.allocate(capacity.prepare(plan(2, 1, 1)));
        assertEquals(50, new GpuFragmentation(specification, new Request[]{new Request(3)}).amount(capacity, 1));
        assertEquals(30, new GpuFragmentation(specification, new Request[]{new Request(4)}).amount(capacity, 1));
        final var mixed = new GpuFragmentation(specification, new Request[]{new Request(5)});
        assertEquals(0, mixed.amount(capacity, 1));
        assertEquals(50, new GpuFragmentation(specification, new Request[]{new Request(7)}).amount(capacity, 1));
        assertEquals(110, new GpuFragmentation(specification,
                new Request[]{new Request(4, 7), new Request(4)}).amount(capacity, 1));
        capacity.allocate(capacity.prepare(plan(6, 1, 3)));
        assertEquals(40, mixed.amount(capacity, 1));
    }

    @Test
    void dedicatedAndNonprodCpuUseTheSameBudgetsAndConversionAsTheLedger() throws Exception {
        final var specification = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 11, 12, 15}, 10, 1, 5, 5),
                alloc(2, new int[]{1, 11, 12, 15}, 1, 1, 4, 4),
                alloc(3, new int[]{1}, 3), alloc(4, new int[]{12}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(2)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 4, 2));
        final var capacity = new CapacityLedger(specification);
        final var metric = new GpuFragmentation(specification, new Request[]{new Request(2)});
        assertEquals(0, metric.amount(capacity, 1));
        final var vcores = capacity.prepare(plan(3, 1));
        capacity.allocate(vcores);
        assertEquals(20, metric.amount(capacity, 1));
        capacity.release(vcores);
        assertEquals(0, metric.amount(capacity, 1));
        final var nonprod = capacity.prepare(plan(4, 1));
        capacity.allocate(nonprod);
        assertEquals(20, metric.amount(capacity, 1));
        capacity.release(nonprod);
        assertEquals(0, metric.amount(capacity, 1));
    }

    @Test
    void referencesUseCpuGpuOnlyWhileActualAtomicPlansConsumeEveryMembersCpu() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 11}, 8, 1), alloc(2, new int[]{1, 2, 11}, 8, 1000, 1),
                alloc(3, new int[]{1}, 4),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 3, 3)));
        final var scorer = new GreedyPlanner(state).gpuFragmentationScorer(new Request[]{new Request(2)});
        assertEquals(20, scorer.applyAsDouble(new Assignment[]{plan(3, 1), plan(4, 1)}));
        assertEquals(8, state.available(1, 1));
        assertEquals(20, state.gpuAvailable(1));
        assertThrows(IllegalArgumentException.class,
                () -> scorer.applyAsDouble(new Assignment[]{plan(2, 1, 1)}));
        assertEquals(20, scorer.applyAsDouble(new Assignment[]{plan(3, 1)}));
    }

    @Test
    void emptyCatalogueScoresZeroAndInvalidInputsFailExplicitly() throws Exception {
        final var records = new one.cloud.benchmark.dataset.PlacementDatasetProtos.Record[]{
                alloc(1, new int[]{11}, 1), alloc(2, new int[]{11}, .5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2)};
        final var planner = new GreedyPlanner(new Engine(model(ClusterSpec.GpuMode.DEVICES_20, records)));
        final var scorer = planner.gpuFragmentationScorer(new Request[0]);
        assertEquals(0, scorer.applyAsDouble(new Assignment[]{plan(2, 1, 1)}));
        assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[0]));
        assertThrows(IllegalArgumentException.class,
                () -> scorer.applyAsDouble(new Assignment[]{plan(2, 1, 1), plan(2, 1, 1)}));
        assertThrows(IllegalArgumentException.class,
                () -> planner.gpuFragmentationScorer(new Request[]{new Request(1)}));
        assertThrows(IllegalArgumentException.class,
                () -> new GreedyPlanner(new Engine(model(records))).gpuFragmentationScorer(new Request[0]));
    }

    private static Assignment plan(int resource, int host, int... gpus) {
        return new Assignment(resource, host, 0, new int[]{0}, new int[0], gpus);
    }

    private static void place(Engine engine, Request request, Assignment... plans) throws Exception {
        engine.schedule(new SchedulingAlgorithm(engine, 42) {
            @Override
            public void schedule(Request ignored) {
                context.place(request, plans);
            }
        }, request);
    }
}
