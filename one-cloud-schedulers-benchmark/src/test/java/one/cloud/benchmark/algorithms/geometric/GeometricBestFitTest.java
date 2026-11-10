package one.cloud.benchmark.algorithms.geometric;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometricBestFitTest {
    @Test
    void heterogeneousHostsUseInitialCapacityRatherThanRemainingCapacity() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 100), alloc(2, new int[]{1}, 20),
                alloc(3, new int[]{1}, 80), alloc(4, new int[]{1}, 5), alloc(5, new int[]{1}, 10),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 3), resource(5, 5, 4));
        final var state = new Engine(model);
        try (final var algorithm = new GeometricBestFit(state, 42)) {
            state.schedule(algorithm, new Request(3), new Request(4), new Request(5));
            assertEquals(1, state.placement(5).host());
            assertEquals(10, state.available(1, 1));
            assertEquals(15, state.available(2, 1));
            assertEquals(6, algorithm.plannerCalls());
        }
    }

    @Test
    void selectionSumsFamilyMeansRatherThanAveragingAllBudgetCells() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 3}, 2, 8), alloc(2, new int[]{1, 3}, 4, 2),
                alloc(3, new int[]{1}, 2), alloc(4, new int[]{1}, 4), alloc(5, new int[]{1, 3}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(2).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 3), resource(5, 5, 4));
        final var state = new Engine(model);
        try (final var algorithm = new GeometricBestFit(state, 42)) {
            state.schedule(algorithm, new Request(5));
            assertEquals(2, state.placement(5).host());
            assertEquals(4, state.placement(5).numa());
            assertEquals(4, algorithm.plannerCalls());
        }
    }

    @Test
    void exactTiesAreSeededAndZeroDemandDoesNotFavorTheFirstOrLastHost() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 1), alloc(2, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 1, 1),
                resource(4, 2, 2), resource(5, 2, 2));
        final var hosts = new HashSet<Integer>();
        for (int seed = 0; seed < 64; seed++) {
            final var first = tieRun(model, seed);
            assertArrayEquals(first, tieRun(model, seed));
            for (final int host : first) {
                hosts.add(host);
            }
        }
        assertEquals(3, hosts.size());
    }

    @Test
    void jointRequestsCommitAtomicallyAndFreshSupplyIsUsedAfterRemoval() throws Exception {
        final var model = model(
                alloc(1, new int[]{2}, 4), alloc(2, new int[]{2}, 2), alloc(3, new int[]{2}, 3),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)
                        .addLinks(Link.newBuilder().setTarget(4).setDomain(1))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setPinnedHost(1)
                        .addLinks(Link.newBuilder().setTarget(6).setDomain(1))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3),
                resource(5, 3, 4), resource(6, 3, 3));
        final var state = new Engine(model);
        final var valid = new Request(3, 4);
        try (final var algorithm = new GeometricBestFit(state, 42)) {
            state.schedule(algorithm, new Request(5, 6));
            assertNull(state.placement(5));
            assertNull(state.placement(6));
            assertEquals(0, state.revision());
            state.schedule(algorithm, valid);
            assertEquals(1, state.placement(3).host());
            assertEquals(1, state.placement(4).host());
            assertEquals(0, state.available(1, 2));
            state.remove(valid);
            assertEquals(4, state.available(1, 2));
            state.schedule(algorithm, valid);
            assertEquals(1, state.placement(3).host());
            assertEquals(1, state.placement(4).host());
            assertEquals(3, state.revision());
            assertTrue(algorithm.plannerCalls() > 0);
        }
    }

    private static int[] tieRun(ClusterSpec model, long seed) throws Exception {
        final var state = new Engine(model);
        try (final var algorithm = new GeometricBestFit(state, seed)) {
            state.schedule(algorithm, new Request(4), new Request(5));
            assertEquals(6, algorithm.plannerCalls());
            return new int[]{state.placement(4).host(), state.placement(5).host()};
        }
    }
}
