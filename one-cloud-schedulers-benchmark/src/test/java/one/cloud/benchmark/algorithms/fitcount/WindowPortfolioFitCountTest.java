package one.cloud.benchmark.algorithms.fitcount;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowPortfolioFitCountTest {
    @Test
    void singletonAndEmptyCallsPreservePersistentRandom20ThroughDeletes() throws Exception {
        final var model = scalarModel(28, 4, 3);
        final var requests = requests(29, 3);
        final var baseline = new Engine(model);
        final var state = new Engine(model);
        try (final var random = new RandomNFitCount(baseline, 44, requests, 20, 10, 1);
                final var window = new WindowPortfolioFitCount(state, 44, requests, 10, 1, true)) {
            for (int pass = 0; pass < 2; pass++) {
                state.schedule(window, new Request[0]);
                for (final var request : requests) {
                    baseline.schedule(random, request);
                    state.schedule(window, request);
                    assertPlan(baseline.placement(request.resource(0)), state.placement(request.resource(0)));
                }
                for (final var request : requests) {
                    baseline.remove(request);
                    state.remove(request);
                }
            }
            assertEquals(6L, window.counters().get("nonempty_windows"));
            assertEquals(0L, window.counters().get("trial_windows"));
            assertEquals(random.plannerCalls(), window.plannerCalls());
        }
    }

    @Test
    void fullIncumbentUsesWindowSeedAndReplaysOwnedAtomicGpuPlans() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11, 17}, 1, 1), alloc(2, new int[]{11, 17}, .6, .4),
                alloc(3, new int[]{11, 17}, .4, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 2), resource(5, 2, 2));
        final var requests = new Request[]{new Request(3, 4), new Request(5)};
        final var state = new Engine(model);
        try (final var window = new WindowPortfolioFitCount(state, 44, requests, 10, 1, true)) {
            final var trial = state.trial(model);
            final var expected = new Assignment[3];
            try (final var baseline = new RandomNFitCount(trial.state(),
                    PortfolioFitCount.mix64(44 ^ PortfolioFitCount.mix64(0)), requests, 20, 10, 1)) {
                trial.state().schedule(baseline, requests);
                for (int resource = 3; resource <= 5; resource++) {
                    expected[resource - 3] = trial.state().placement(resource).copy();
                }
            }
            trial.reset();
            state.schedule(window, requests);
            for (int resource = 3; resource <= 5; resource++) {
                assertPlan(expected[resource - 3], state.placement(resource));
            }
            assertEquals(1L, window.counters().get("trial_trajectories"));
            assertEquals(1L, window.counters().get("challenger_skipped_full"));
            assertEquals(2L, window.counters().get("prefix_replay_steps"));
            state.remove(requests[1]);
            state.remove(requests[0]);
            assertEquals(1, state.available(1, 11));
            assertEquals(1, state.available(2, 17));
        }
    }

    @Test
    void strictSupersetComparesOriginalIndicesRatherThanAdmissionCount() {
        final Assignment[] plan = {new Assignment(1, 1, 0, new int[]{0}, new int[0])};
        final Assignment[][] incumbent = {plan, null, null};
        assertTrue(WindowPortfolioFitCount.strictSuperset(incumbent, new Assignment[][]{plan, plan, null}));
        assertFalse(WindowPortfolioFitCount.strictSuperset(incumbent, new Assignment[][]{plan, null, null}));
        assertFalse(WindowPortfolioFitCount.strictSuperset(incumbent, new Assignment[][]{null, plan, plan}));
        assertFalse(WindowPortfolioFitCount.strictSuperset(incumbent, new Assignment[][]{null, plan, null}));
        assertNotNull(incumbent[0]);
        assertNull(incumbent[1]);
    }

    @Test
    void challengerPublishesOnlyACompleteStrictSupersetAndControlKeepsIncumbent() throws Exception {
        final var probeModel = scalarModel(21, 1, 2);
        final var first = new Request(22);
        long seed = -1;
        int incumbentHost = 0;
        int challengerHost = 0;
        for (long candidate = 0; candidate < 64 && seed < 0; candidate++) {
            final long windowSeed = PortfolioFitCount.mix64(candidate ^ PortfolioFitCount.mix64(0));
            final var baseline = new Engine(probeModel);
            final var alternative = new Engine(probeModel);
            try (final var random = new RandomNFitCount(baseline, windowSeed, new Request[0], 20, 10, 1);
                    final var portfolio = new PortfolioFitCount(alternative, windowSeed, new Request[0],
                            20, 4, 4, 10, 1)) {
                baseline.schedule(random, first);
                alternative.schedule(portfolio, first);
                if (baseline.placement(22).host() != alternative.placement(22).host()) {
                    seed = candidate;
                    incumbentHost = baseline.placement(22).host();
                    challengerHost = alternative.placement(22).host();
                }
            }
        }
        assertTrue(seed >= 0, "Fixture must expose different complete trajectories");
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 1));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        records.add(rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(incumbentHost)));
        for (int host = 1; host <= 21; host++) {
            records.add(resource(host, 1, 1));
        }
        records.add(resource(22, 1, 2));
        records.add(resource(23, 1, 3));
        final var model = model(records.toArray(Record[]::new));
        final var requests = requests(22, 2);
        for (final boolean compare : new boolean[]{false, true}) {
            final var state = new Engine(model);
            try (final var window = new WindowPortfolioFitCount(state, seed, new Request[0], 10, 1, compare)) {
                state.schedule(window, requests);
                assertEquals(compare ? challengerHost : incumbentHost, state.placement(22).host());
                if (compare) {
                    assertEquals(incumbentHost, state.placement(23).host());
                } else {
                    assertNull(state.placement(23));
                }
                assertEquals(compare ? 1L : 0L, window.counters().get("challenger_superset_wins"));
                assertEquals(compare ? 2L : 1L, window.counters().get("trial_trajectories"));
                assertEquals(2L, window.counters().get("prefix_replay_steps"));
            }
        }
    }

    @Test
    void snapshotBudgetSeparatesIncumbentFallbackFromChallengerRejection() throws Exception {
        final var model = scalarModel(1, 1, 2);
        final var requests = requests(2, 2);
        for (final long budget : new long[]{171, 172}) {
            final var state = new Engine(model);
            try (final var window = new WindowPortfolioFitCount(state, 44, requests, 10, 1, true, budget)) {
                state.schedule(window, requests);
                assertNotNull(state.placement(2));
                assertNull(state.placement(3));
                assertEquals(budget == 171 ? 1L : 0L, window.counters().get("fallback_incumbent_workspace"));
                assertEquals(budget == 172 ? 1L : 0L, window.counters().get("fallback_challenger_workspace"));
                assertEquals(budget == 171 ? 0L : 2L, window.counters().get("prefix_replay_steps"));
                assertTrue(window.counters().get("snapshot_payload_peak_bytes") <= budget);
                state.remove(requests[0]);
                assertEquals(1, state.available(1, 1));
            }
        }
    }

    @Test
    void capabilityFallbackIsNarrowAndOversizedWindowsStaySequential() throws Exception {
        final var model = scalarModel(2, 200, 101);
        final var requests = requests(3, 101);
        final var state = new Engine(model);
        try (final var window = new WindowPortfolioFitCount(state, 44, requests, 10, 1, true)) {
            state.schedule(window, requests);
            assertEquals(1L, window.counters().get("fallback_oversized_window"));
            assertEquals(0L, window.counters().get("trial_windows"));
            for (final var request : requests) {
                assertNotNull(state.placement(request.resource(0)));
            }
        }
        for (final boolean unavailable : new boolean[]{true, false}) {
            final var isolated = new Engine(scalarModel(1, 1, 2));
            final var pending = requests(2, 2);
            final var context = (SchedulingContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SchedulingContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("trial")) {
                            if (unavailable) {
                                throw new UnsupportedOperationException("unavailable");
                            }
                            throw new IllegalStateException("unexpected failure");
                        }
                        try {
                            return method.invoke(isolated, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            try (final var window = new WindowPortfolioFitCount(context, 44, pending)) {
                final var wrapper = new SchedulingAlgorithm(isolated, 44) {
                    @Override
                    public void schedule(Request request) throws Exception {
                        window.schedule(request);
                    }

                    @Override
                    public void schedule(Request[] requests) throws Exception {
                        window.schedule(requests);
                    }

                    @Override
                    public void placed(Assignment placement) {
                        window.placed(placement);
                    }

                    @Override
                    public void removed(Assignment placement) {
                        window.removed(placement);
                    }
                };
                if (unavailable) {
                    isolated.schedule(wrapper, pending);
                    assertNotNull(isolated.placement(2));
                    assertNull(isolated.placement(3));
                    assertEquals(1L, window.counters().get("fallback_trial_unavailable"));
                    isolated.remove(pending[0]);
                    assertEquals(1, isolated.available(1, 1));
                } else {
                    assertThrows(IllegalStateException.class, () -> isolated.schedule(wrapper, pending));
                    assertNull(isolated.placement(2));
                    assertEquals(0L, window.counters().get("fallback_trial_unavailable"));
                }
            }
        }
    }

    private static ClusterSpec scalarModel(int hosts, int capacity, int units) throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, capacity));
        records.add(alloc(2, new int[]{1}, 1));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        for (int host = 1; host <= hosts; host++) {
            records.add(resource(host, 1, 1));
        }
        for (int unit = 1; unit <= units; unit++) {
            records.add(resource(hosts + unit, 2, 2));
        }
        return model(records.toArray(Record[]::new));
    }

    private static Request[] requests(int first, int count) {
        final var result = new Request[count];
        for (int index = 0; index < count; index++) {
            result[index] = new Request(first + index);
        }
        return result;
    }

    private static void assertPlan(Assignment expected, Assignment actual) {
        assertNotNull(actual);
        assertEquals(expected.resource(), actual.resource());
        assertEquals(expected.host(), actual.host());
        assertEquals(expected.numa(), actual.numa());
        assertArrayEquals(expected.diskOffsets(), actual.diskOffsets());
        assertArrayEquals(expected.devices(), actual.devices());
        assertArrayEquals(expected.gpus(), actual.gpus());
    }
}
