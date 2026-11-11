package one.cloud.benchmark.algorithms.kubernetes;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring.Strategy;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KubernetesResourceScoringTest {
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
