package one.cloud.benchmark.algorithms.ilp;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveIlpTest {
    @Test
    void rescuesOnlyWithEnoughMovesAndKeepsOriginalDeleteRequests() throws Exception {
        exercise(1, 4, false, false);
        exercise(2, 4, true, false);
    }

    @Test
    void exactValidationRejectsJointNetworkOvercommitWithoutChangingState() throws Exception {
        exercise(2, 3, false, true);
    }

    private static void exercise(int maxMoves, int destinationNetwork, boolean rescued, boolean rejected) throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1, 2, 3}, 8, 8, 4), alloc(2, new int[]{1, 2, 3}, 7, 1000, destinationNetwork),
                alloc(3, new int[]{1, 2, 3}, 2, 3, 2), alloc(4, new int[]{1, 2}, 8, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 3, 2), resource(5, 4, 2)));
        final var first = new Request(3);
        final var second = new Request(4);
        final var incoming = new Request(5);
        try (final var algorithm = new ReactiveIlp(state, 42, maxMoves, 5000)) {
            state.schedule(algorithm, first, second);
            assertEquals(1, state.placement(3).host());
            assertEquals(1, state.placement(4).host());
            assertEquals(0L, algorithm.counters().get("ilp_fallbacks"));
            final long revision = state.revision();
            state.schedule(algorithm, incoming);
            assertEquals(1L, algorithm.counters().get("ilp_fallbacks"));
            assertTrue(algorithm.counters().get("ilp_solves") > 0);
            assertEquals(rescued ? 1L : 0L, algorithm.counters().get("ilp_rescued"));
            assertEquals(rescued ? 2L : 0L, algorithm.counters().get("ilp_moved_units"));
            assertEquals(rejected, algorithm.counters().get("ilp_rejected_plans") > 0);
            assertEquals(rescued ? 2 : 1, state.placement(3).host());
            assertEquals(rescued ? 2 : 1, state.placement(4).host());
            assertEquals(rescued ? 2 : 0, state.drainRelocations().size());
            assertTrue(state.drainRelocations().isEmpty());
            if (rescued) {
                assertEquals(1, state.placement(5).host());
                state.remove(incoming);
            } else {
                assertNull(state.placement(5));
                assertEquals(revision, state.revision());
                assertEquals(4, state.available(1, 1));
                assertEquals(0, state.available(1, 3));
            }
            state.remove(second);
            state.remove(first);
            assertEquals(8, state.available(1, 1));
            assertEquals(7, state.available(2, 1));
            assertEquals(destinationNetwork, state.available(2, 3));
        }
    }
}
