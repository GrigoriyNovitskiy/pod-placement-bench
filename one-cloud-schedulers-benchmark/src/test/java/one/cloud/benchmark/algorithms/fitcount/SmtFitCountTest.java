package one.cloud.benchmark.algorithms.fitcount;

import java.io.IOException;
import java.util.ArrayList;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Domain;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SmtFitCountTest {
    @BeforeAll
    static void requireZ3() throws Exception {
        try {
            final var process = new ProcessBuilder("z3", "-version").start();
            try {
                assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0,
                        "The optional Z3 executable is required");
            } finally {
                process.destroyForcibly();
            }
        } catch (IOException unavailable) {
            assumeTrue(false, "The optional Z3 executable is required");
        }
    }

    @Test
    void splitHostAtomicUnitUsesBothPinsAndReleasesTogether() throws Exception {
        final var specification = model(
                alloc(1, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 1, 2), resource(4, 1, 3));
        final var unit = new Request(3, 4);
        final var baseline = new Engine(specification);
        try (final var fit = new FitCount(baseline, 42, new Request[]{unit}, 128, 256)) {
            baseline.schedule(fit, unit);
            assertNull(baseline.placement(3));
        }
        final var state = new Engine(specification);
        try (final var smt = new SmtFitCount(state, 42, new Request[]{unit}, 128, 256)) {
            state.schedule(smt, unit);
            assertEquals(1, state.placement(3).host());
            assertEquals(2, state.placement(4).host());
            assertEquals(1, state.revision());
            state.remove(unit);
            assertNull(state.placement(3));
            assertNull(state.placement(4));
            assertEquals(1, state.available(1, 1));
            assertEquals(1, state.available(2, 1));
        }
    }

    @Test
    void registryRunsNumaAndRamAssignmentsThroughTheCommonEngine() throws Exception {
        final var specification = model(
                alloc(1, new int[]{1, 2}, 4, 3), alloc(2, new int[]{1, 2}, 2, 2),
                alloc(3, new int[]{1, 2}, 2, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setPinnedHost(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 3, 4), resource(5, 2, 4));
        final var unit = new Request(4, 5);
        final var state = new Engine(specification);
        try (final var smt = AlgorithmRegistry.create(AlgorithmRegistry.defaults("smt"), state,
                new Request[]{unit})) {
            state.schedule(smt, unit);
            assertEquals(3, state.placement(4).numa());
            assertEquals(2, state.placement(5).numa());
            assertEquals(0, state.available(1, 1));
            assertEquals(0, state.available(1, 2));
            assertEquals(1, smt.counters().get("optimized_windows"));
        }
    }

    @Test
    void jointSelectionRescuesPinnedRequestAfterFlexibleRequest() throws Exception {
        final var specification = pinnedTrap(false);
        final var pending = new Request[]{new Request(3), new Request(4)};
        final var catalogue = new Request[]{new Request(5)};
        final var baseline = new Engine(specification);
        try (final var fit = new FitCount(baseline, 42, catalogue, 128, 256)) {
            baseline.schedule(fit, pending);
            assertEquals(1, baseline.placement(3).host());
            assertNull(baseline.placement(4));
        }
        final var state = new Engine(specification);
        try (final var smt = new SmtFitCount(state, 42, catalogue, 128, 256)) {
            state.schedule(smt, pending);
            assertEquals(2, state.placement(3).host());
            assertEquals(1, state.placement(4).host());
            assertEquals(2, state.revision());
            assertEquals(1, smt.counters().get("improved_windows"));
        }
    }

    @Test
    void earlierUnitEnablesDependencyAndReverseOrderPreservesPrefixValidity() throws Exception {
        final var specification = pinnedTrap(true);
        for (final boolean reversed : new boolean[]{false, true}) {
            final var state = new Engine(specification);
            final var pending = reversed ? new Request[]{new Request(4), new Request(3)}
                    : new Request[]{new Request(3), new Request(4)};
            try (final var smt = new SmtFitCount(state, 42, new Request[]{new Request(5)}, 128, 256)) {
                state.schedule(smt, pending);
                assertNotNull(state.placement(3));
                if (reversed) {
                    assertNull(state.placement(4));
                    assertEquals(1, state.revision());
                } else {
                    assertEquals(2, state.placement(3).host());
                    assertEquals(1, state.placement(4).host());
                    assertEquals(2, state.revision());
                    state.remove(pending[1]);
                    state.remove(pending[0]);
                    assertEquals(0, state.groupCount(1));
                }
            }
        }
    }

    @Test
    void secondaryObjectiveScoresCombinedResidualCapacity() throws Exception {
        final var specification = model(
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2),
                resource(5, 1, 2));
        final var state = new Engine(specification);
        try (final var smt = new SmtFitCount(state, 42, new Request[]{new Request(5)}, 128, 256)) {
            state.schedule(smt, new Request(3), new Request(4));
            assertEquals(state.placement(3).host(), state.placement(4).host());
            assertTrue(state.available(1, 1) == 2 || state.available(2, 1) == 2);
        }
    }

    @Test
    void crossingDomainCapsAreCheckedOnWholeDecisions() throws Exception {
        final var specification = model(
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(1))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(1))),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(1))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(2))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(2))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(1))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(3).setMax(1).setHard(true))
                        .addLimits(Limit.newBuilder().setDomain(7).setMax(1).setHard(true))),
                resource(1, 1, 1), resource(2, 1, 2), resource(3, 1, 3), resource(4, 2, 4),
                resource(5, 2, 4));
        final var state = new Engine(specification);
        final var pending = new Request[]{new Request(4), new Request(5)};
        try (final var smt = new SmtFitCount(state, 42, pending, 128, 256)) {
            state.schedule(smt, pending);
            assertEquals(5, state.placement(4).host() + state.placement(5).host());
            assertEquals(2, state.groupCount(1));
            assertEquals(1, state.domainCount(1, 3, 1));
            assertEquals(1, state.domainCount(1, 7, 1));
        }
    }

    @Test
    void floatingOccupancyAndOversizedWindowsRetainValidFallback() throws Exception {
        final var fractional = model(
                alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, .5), alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2));
        final var state = new Engine(fractional);
        try (final var smt = new SmtFitCount(state, 42, new Request[]{new Request(3)}, 128, 256)) {
            state.schedule(smt, new Request(2));
            state.schedule(smt, new Request(3));
            assertEquals(2.5, state.available(1, 1));
            assertEquals(2, state.revision());
            assertEquals(2, smt.counters().get("fallback_non_integer"));
        }
        final int[] demands = {1, 1, 1, 1, 1, 1, 1, 1, 1};
        final var large = scalar(new int[]{5, 5}, demands, new int[demands.length]);
        final var pending = requests(3, demands.length);
        final var oversized = new Engine(large);
        try (final var smt = new SmtFitCount(oversized, 42, pending, 128, 256)) {
            oversized.schedule(smt, pending);
            assertEquals(9, oversized.revision());
            assertEquals(1, smt.counters().get("fallback_window_limit"));
            assertEquals(1, oversized.available(1, 1) + oversized.available(2, 1));
        }
    }

    @Test
    void fractionalLiveCpuDebitKeepsTheSavedPlanWithoutIntegerRounding() throws Exception {
        final var specification = model(
                alloc(1, new int[]{1, 15}, 4, 4), alloc(2, new int[]{15}, 1),
                alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(.5)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2));
        final var state = new Engine(specification);
        try (final var smt = new SmtFitCount(state, 42, new Request[]{new Request(3)}, 128, 256)) {
            state.schedule(smt, new Request(2));
            assertEquals(3.5, state.available(1, 1));
            state.schedule(smt, new Request(3));
            assertEquals(2.5, state.available(1, 1));
            assertEquals(2, state.revision());
            assertEquals(1, smt.counters().get("fallback_unsupported_resources"));
            assertEquals(1, smt.counters().get("fallback_non_integer"));
            assertEquals(0L, smt.counters().getOrDefault("solver_calls", 0L));
        }
    }

    @Test
    void searchBudgetKeepsTheCompleteSequentialIncumbent() throws Exception {
        final var specification = pinnedTrap(false);
        final var catalogue = new Request[]{new Request(5)};
        final var pending = new Request[]{new Request(3), new Request(4)};
        final var baseline = new Engine(specification);
        try (final var fit = new FitCount(baseline, 42, catalogue, 128, 256)) {
            baseline.schedule(fit, pending);
        }
        final var limited = new Engine(specification);
        try (final var smt = new SmtFitCount(limited, 42, catalogue, 128, 256, 1, 64, 1_000_000)) {
            limited.schedule(smt, pending);
            assertEquals(1, smt.counters().get("fallback_work_budget"));
            assertEquals(baseline.placement(3).host(), limited.placement(3).host());
            assertNull(limited.placement(4));
            assertEquals(baseline.available(1, 1), limited.available(1, 1));
            assertEquals(baseline.available(2, 1), limited.available(2, 1));
            assertEquals(baseline.revision(), limited.revision());
        }
    }

    @Test
    void maximumAdmissionMatchesExhaustiveTinyIntegerPacking() throws Exception {
        final var random = new SplittableRandom(42);
        for (int attempt = 0; attempt < 12; attempt++) {
            final int[] capacities = {random.nextInt(2, 6), random.nextInt(2, 6)};
            final int[] demands = new int[4];
            final int[] pins = new int[4];
            for (int unit = 0; unit < demands.length; unit++) {
                demands[unit] = random.nextInt(1, 5);
                pins[unit] = random.nextInt(3);
            }
            final var specification = scalar(capacities, demands, pins);
            final var pending = requests(3, demands.length);
            final var state = new Engine(specification);
            try (final var smt = new SmtFitCount(state, 42, pending, 128, 256)) {
                state.schedule(smt, pending);
                assertEquals(exhaustive(demands, pins, 0, capacities[0], capacities[1]), state.revision(),
                        "Miniature instance " + attempt);
            }
        }
    }

    private static ClusterSpec pinnedTrap(boolean dependency) throws IOException {
        final var dependent = Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(1);
        if (dependency) {
            dependent.addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(1).setDomain(1));
        }
        return model(
                alloc(1, new int[]{1}, 1), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(dependent), rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 1, 2), resource(4, 1, 3),
                resource(5, 2, 4));
    }

    private static ClusterSpec scalar(int[] capacities, int[] demands, int[] pins) throws IOException {
        final var records = new ArrayList<Record>();
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        for (int host = 0; host < capacities.length; host++) {
            records.add(alloc(host + 1, new int[]{1}, capacities[host]));
            records.add(resource(host + 1, host + 1, 1));
        }
        for (int unit = 0; unit < demands.length; unit++) {
            final int resource = capacities.length + unit + 1;
            records.add(alloc(resource, new int[]{1}, demands[unit]));
            records.add(rules(Constraints.newBuilder().setId(unit + 2).setKind(Kind.POD)
                    .setPinnedHost(pins[unit])));
            records.add(resource(resource, resource, unit + 2));
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

    private static int exhaustive(int[] demands, int[] pins, int unit, int first, int second) {
        if (unit == demands.length) {
            return 0;
        }
        int best = exhaustive(demands, pins, unit + 1, first, second);
        if (pins[unit] != 2 && demands[unit] <= first) {
            best = Math.max(best, 1 + exhaustive(demands, pins, unit + 1, first - demands[unit], second));
        }
        if (pins[unit] != 1 && demands[unit] <= second) {
            best = Math.max(best, 1 + exhaustive(demands, pins, unit + 1, first, second - demands[unit]));
        }
        return best;
    }
}
