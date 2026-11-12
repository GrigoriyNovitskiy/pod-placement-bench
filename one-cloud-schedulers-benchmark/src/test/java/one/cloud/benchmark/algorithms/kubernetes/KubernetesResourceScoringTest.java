package one.cloud.benchmark.algorithms.kubernetes;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring.Strategy;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KubernetesResourceScoringTest {
    @Test
    void sharedLeastAllocatedScorerPreservesIntegerRoundingAndPlanGeometry() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1, 2, 3}, 100, 100, 10),
                alloc(2, new int[]{1, 2, 3}, 100, 100, 40), alloc(3, new int[]{3}, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2)));
        final var planner = new GreedyPlanner(state);
        final var scorer = KubernetesResourceScoring.scorer(planner, Strategy.LEAST_ALLOCATED);
        state.schedule(new SchedulingAlgorithm(state, 44) {
            @Override
            public void schedule(Request request) {
                for (int host = 1; host <= 2; host++) {
                    final var plans = planner.plan(request, host, 0);
                    final Assignment before = plans[0].copy();
                    assertEquals(host == 1 ? 73 : 93, scorer.applyAsDouble(plans));
                    assertEquals(before.resource(), plans[0].resource());
                    assertEquals(before.host(), plans[0].host());
                    assertEquals(before.numa(), plans[0].numa());
                    assertArrayEquals(before.diskOffsets(), plans[0].diskOffsets());
                    assertArrayEquals(before.devices(), plans[0].devices());
                    assertArrayEquals(before.gpus(), plans[0].gpus());
                }
                assertEquals(0, state.revision());
            }
        }, new Request(3));
    }

    @Test
    void spreadingPackingAndUtilizationCurveSelectDifferentHosts() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 100, 100),
                alloc(2, new int[]{1, 2}, 20, 20), alloc(3, new int[]{1, 2}, 75, 75),
                alloc(4, new int[]{1, 2}, 85, 85), alloc(5, new int[]{1, 2}, 10, 10),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setPinnedHost(3)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 1, 1),
                resource(4, 2, 2), resource(5, 3, 3), resource(6, 4, 4), resource(7, 5, 5));
        assertEquals(1, place(model, Strategy.LEAST_ALLOCATED, 4, 5, 6, 7));
        assertEquals(3, place(model, Strategy.MOST_ALLOCATED, 4, 5, 6, 7));
        assertEquals(2, place(model, Strategy.REQUESTED_TO_CAPACITY_RATIO, 4, 5, 6, 7));
    }

    @Test
    void balancedAllocationPrefersImprovingBalanceOverLowestFinalSpread() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 100, 100),
                alloc(2, new int[]{1, 2}, 20, 65), alloc(3, new int[]{1, 2}, 45, 50),
                alloc(4, new int[]{1}, 20),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 3), resource(5, 4, 4));
        assertEquals(1, place(model, Strategy.BALANCED_ALLOCATION, 3, 4, 5));
    }

    @Test
    void mostAllocatedUsesHostSpecificDedicatedCoreDebit() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 15}, 100, 4), alloc(2, new int[]{1}, 5),
                alloc(3, new int[]{1, 15}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(10)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.HOST).setCpuPower(20)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 2), resource(3, 2, 3), resource(4, 3, 4));
        assertEquals(2, place(model, Strategy.MOST_ALLOCATED, 3, 4));
    }

    @Test
    void networkCapacityInfluencesSpreadingPackingAndTheUtilizationCurve() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2, 3}, 100, 100, 10), alloc(2, new int[]{1, 2, 3}, 100, 100, 40),
                alloc(3, new int[]{3}, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2));
        assertEquals(2, place(model, Strategy.LEAST_ALLOCATED, 3));
        assertEquals(1, place(model, Strategy.MOST_ALLOCATED, 3));
        assertEquals(1, place(model, Strategy.REQUESTED_TO_CAPACITY_RATIO, 3));
    }

    @Test
    void balancedAllocationUsesNetworkUtilizationEvenForNetworkOnlyRequests() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 3}, 100, 100), alloc(2, new int[]{1, 3}, 60, 10),
                alloc(3, new int[]{1, 3}, 20, 25), alloc(4, new int[]{3}, 20),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 3), resource(5, 4, 4));
        assertEquals(1, place(model, Strategy.BALANCED_ALLOCATION, 3, 4, 5));
    }

    @Test
    void diskIopsAffectRankingWithIdenticalSpaceAndHostCapacity() throws Exception {
        final var model = model(
                alloc(1, new int[0]), alloc(2, new int[]{18, 20}, 10, 100),
                alloc(3, new int[]{18, 20}, 10, 1000),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.DISK).setParent(2).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD)
                        .addDisks(DiskPlan.newBuilder().setMedia(Media.HDD).setExtents(2).setIops(80)
                                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 3), resource(5, 1, 4));
        assertEquals(2, place(model, Strategy.LEAST_ALLOCATED, 5));
        assertEquals(1, place(model, Strategy.MOST_ALLOCATED, 5));
    }

    @Test
    void numaCapacityChangesWhichPlanWinsOnTheSameHost() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 100), alloc(2, new int[]{1}, 50),
                alloc(3, new int[]{1}, 35), alloc(4, new int[]{1}, 5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 4, 4));
        final var engine = new Engine(model);
        try (final var algorithm = new KubernetesResourceScoring(engine, 42, Strategy.LEAST_ALLOCATED)) {
            engine.schedule(algorithm, new Request(4), new Request(5));
            assertEquals(2, engine.placement(4).numa());
            assertEquals(3, engine.placement(5).numa());
        }
    }

    @Test
    void gpuScoringDistinguishesCardsWhenHostTotalsTie() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .6), alloc(3, new int[]{11}, .8),
                alloc(4, new int[]{11}, .4), alloc(5, new int[]{11}, .2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2),
                resource(5, 3, 3), resource(6, 4, 3), resource(7, 5, 4));
        assertEquals(1, place(model, Strategy.LEAST_ALLOCATED, 3, 4, 5, 6, 7));
        assertEquals(2, place(model, Strategy.MOST_ALLOCATED, 3, 4, 5, 6, 7));
    }

    private static int place(ClusterSpec model, Strategy strategy, int... resources) throws Exception {
        final var engine = new Engine(model);
        try (final var algorithm = new KubernetesResourceScoring(engine, 42, strategy)) {
            for (final int resource : resources) {
                engine.schedule(algorithm, new Request(resource));
            }
            assertEquals((long) model.hostCount * resources.length, algorithm.plannerCalls());
            return engine.placement(resources[resources.length - 1]).host();
        }
    }
}
