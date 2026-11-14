package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring.Strategy;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.candidates;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitCountTest {
    @Test
    void allHostMaskedProfilesMatchRandomNThroughEvictionAndRemoval() throws Exception {
        final var model = model(alloc(1, new int[]{1}, 10), alloc(2, new int[]{1}, 12),
                alloc(3, new int[]{1}, 14), alloc(4, new int[]{1}, 3),
                alloc(5, new int[]{1}, 4), alloc(6, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 1),
                resource(4, 4, 2), resource(5, 5, 2), resource(6, 6, 2));
        final var units = new Request[]{new Request(4), new Request(5), new Request(6)};
        final var baseline = new Engine(model);
        final var masked = new Engine(model);
        try (final var random = new RandomNFitCount(baseline, 44, units, 2, 10, 1);
                final var scheduler = new MaskedFitCount(masked, 44, units, 2, 10, 1)) {
            for (int pass = 0; pass < 4; pass++) {
                for (final var request : units) {
                    baseline.schedule(random, request);
                    masked.schedule(scheduler, request);
                    assertEquals(baseline.placement(request.resource(0)).host(),
                            masked.placement(request.resource(0)).host());
                }
                for (final var request : units) {
                    baseline.remove(request);
                    masked.remove(request);
                }
            }
            assertEquals(random.plannerCalls(), scheduler.plannerCalls());
            assertEquals("true", scheduler.parameters().get("masked_enabled"));
            assertEquals(12L, scheduler.counters().get("selector_calls"));
        }
    }

    @Test
    void maskedProfilesCanonicalizeIntersectionsAndPinsWithoutMergingGeometry() throws Exception {
        final var model = model(alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setCandidates(2)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setCandidates(3).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD).setCandidates(4)),
                candidates(2, 1), candidates(3, 1, 2), candidates(4, 2),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2),
                resource(4, 2, 3), resource(5, 2, 4), resource(6, 2, 5));
        final var catalogue = new Request[]{new Request(3), new Request(4), new Request(5),
                new Request(3), new Request(3, 6), new Request(3, 4), new Request(6)};
        final var state = new Engine(model);
        final var compiler = new MaskedFitCountOrder.Compiler(model, 20);
        final var cache = new FitCountCache(state, new GreedyPlanner(state), catalogue, 10, 1, compiler);
        final var order = compiler.finish(cache, 10);
        cache.attach(order);
        assertEquals(4L, order.counters().get("logical_profiles"));
        assertEquals(2L, order.counters().get("physical_families"));
        assertEquals(3L, order.counters().get("distinct_masks"));
        assertEquals(1L, order.counters().get("empty_mask_profiles"));
        order.beginOrder();
        final var empty = order.next();
        assertEquals(0L, order.total(empty));
        assertEquals(4, empty.ordinal);
        final var frequent = order.next();
        assertEquals(0, frequent.ordinal);
        assertEquals(4, frequent.frequency);
        assertEquals(4L, order.total(frequent));
    }

    @Test
    void maskedFamilyRefreshesOncePerGenerationAcrossRepeatedProfileReads() throws Exception {
        final var model = model(alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 2),
                alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2),
                resource(4, 2, 3), resource(5, 3, 4));
        final var catalogue = new Request[]{new Request(3), new Request(4)};
        final var state = new Engine(model);
        final var planner = new GreedyPlanner(state);
        final var compiler = new MaskedFitCountOrder.Compiler(model, 20);
        final var cache = new FitCountCache(state, planner, catalogue, 10, 1, compiler);
        final var order = compiler.finish(cache, 10);
        cache.attach(order);
        final var demand = new Request(5);
        try (final var scheduler = new FitCount(state, 44, catalogue, 10, 1)) {
            for (int step = 0; step < 3; step++) {
                if (step == 1) {
                    state.schedule(scheduler, demand);
                    cache.changed(state.placement(5).host());
                } else if (step == 2) {
                    final int host = state.placement(5).host();
                    state.remove(demand);
                    cache.changed(host);
                }
                for (int pass = 0; pass < 3; pass++) {
                    order.beginOrder();
                    int seen = 0;
                    for (var profile = order.next(); profile != null; profile = order.next()) {
                        final int host = profile.ordinal + 1;
                        final int expected = planner.fitCount(profile.family.request, host, 10);
                        assertEquals(expected, order.total(profile));
                        assertEquals(expected, cache.count(profile.family, host));
                        cache.counts(profile.family);
                        seen++;
                    }
                    assertEquals(2, seen);
                    assertEquals(step + 1L, order.counters().get("masked_family_refreshes"));
                }
            }
        }
    }

    @Test
    void maskedLazyOrderMatchesFreshProjectionAcrossEvictionsAndHostDeltas() throws Exception {
        final var model = model(alloc(1, new int[]{1}, 13), alloc(2, new int[]{1}, 9),
                alloc(3, new int[]{1}, 2), alloc(4, new int[]{1}, 3), alloc(5, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 4),
                resource(5, 3, 3), resource(6, 5, 4), resource(7, 4, 2));
        final var catalogue = new Request[]{new Request(3), new Request(4), new Request(5),
                new Request(3), new Request(7)};
        for (final int cacheRows : new int[]{1, 8}) {
            final var state = new Engine(model);
            final var planner = new GreedyPlanner(state);
            final var compiler = new MaskedFitCountOrder.Compiler(model, 20);
            final var cache = new FitCountCache(state, planner, catalogue, 10, cacheRows, compiler);
            final var order = compiler.finish(cache, 10);
            cache.attach(order);
            final var demand = new Request(6);
            final var scheduler = new FitCount(state, 44, catalogue, 10, cacheRows);
            for (int step = 0; step < 5; step++) {
                if (step > 0) {
                    if (step % 2 == 1) {
                        state.schedule(scheduler, demand);
                        cache.changed(state.placement(6).host());
                    } else {
                        final int host = state.placement(6).host();
                        state.remove(demand);
                        cache.changed(host);
                    }
                }
                order.beginOrder();
                double previous = -1;
                int previousOrdinal = -1;
                int count = 0;
                for (var profile = order.next(); profile != null; profile = order.next()) {
                    long expected = 0;
                    for (int host = 1; host <= model.hostCount; host++) {
                        if (order.allows(profile, host)) {
                            expected += planner.fitCount(profile.family.request, host, 10);
                        }
                    }
                    assertEquals(expected, order.total(profile));
                    final double weighted = expected / (double) profile.frequency;
                    assertTrue(weighted > previous || weighted == previous && profile.ordinal > previousOrdinal);
                    previous = weighted;
                    previousOrdinal = profile.ordinal;
                    count++;
                    assertTrue(cache.cachedShapes() <= cacheRows);
                }
                assertEquals(4, count);
            }
        }
    }

    @Test
    void maskedInterleavedFamiliesRecomputePhysicalLossAfterAnotherFamily() throws Exception {
        final var model = model(alloc(1, new int[]{1, 2}, 2, 2), alloc(2, new int[]{1, 2}, 3, 2),
                alloc(3, new int[]{1, 2}, 4, 2), alloc(4, new int[]{1}, 2),
                alloc(5, new int[]{2}, 2), alloc(6, new int[]{1, 2}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setCandidates(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                candidates(2, 2, 3), resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 1),
                resource(4, 4, 2), resource(5, 5, 4), resource(6, 4, 3), resource(7, 6, 4));
        final var catalogue = new Request[]{new Request(4), new Request(5), new Request(6)};
        final var probe = new Engine(model);
        final var compiler = new MaskedFitCountOrder.Compiler(model, 20);
        final var cache = new FitCountCache(probe, new GreedyPlanner(probe), catalogue, 10, 1, compiler);
        final var order = compiler.finish(cache, 10);
        cache.attach(order);
        order.beginOrder();
        final var first = order.next();
        final var middle = order.next();
        final var last = order.next();
        assertEquals(0, first.ordinal);
        assertEquals(1, middle.ordinal);
        assertEquals(2, last.ordinal);
        assertEquals(first.family, last.family);
        for (final int rows : new int[]{1, 8}) {
            final var state = new Engine(model);
            try (final var scheduler = new MaskedFitCount(state, 0, catalogue, 20, 10, rows)) {
                // The middle RAM family ties hosts 2/3; the final CPU profile prefers odd CPU capacity.
                state.schedule(scheduler, new Request(7));
                assertEquals(2, state.placement(7).host());
            }
        }
    }

    @Test
    void maskedZeroSupplyStillPreservesNegativePhysicalLoss() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 7, 2), alloc(2, new int[]{1, 2}, 4, 1),
                alloc(3, new int[]{1, 2}, 3, 1), alloc(4, new int[]{1, 2}, 2, 1),
                alloc(5, new int[]{1}, 4), alloc(6, new int[]{2}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 0, 1), resource(3, 2, 2), resource(4, 3, 3),
                resource(5, 4, 4), resource(6, 5, 4), resource(7, 6, 5));
        final var state = new Engine(model);
        final var sample = new Request(5, 6);
        final var planner = new GreedyPlanner(state);
        assertEquals(0, planner.fitCount(sample, 1, 10));
        try (final var scheduler = new MaskedFitCount(state, 0, new Request[]{sample}, 20, 10, 1)) {
            state.schedule(scheduler, new Request(7));
            assertEquals(3, state.placement(7).numa());
            assertEquals(1L, scheduler.counters().get("logical_profiles"));
        }
    }

    @Test
    void maskedProfileLimitFallsBackWithoutTruncatingThePhysicalCatalogue() throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 10));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        records.add(resource(1, 1, 1));
        final var catalogue = new Request[MaskedFitCountOrder.PROFILE_LIMIT + 1];
        for (int index = 0; index < catalogue.length; index++) {
            records.add(alloc(index + 2, new int[]{1}, index + 1));
            records.add(resource(index + 2, index + 2, 2));
            catalogue[index] = new Request(index + 2);
        }
        final var model = model(records.toArray(Record[]::new));
        final var state = new Engine(model);
        final var compiler = new MaskedFitCountOrder.Compiler(model, 20);
        final var cache = new FitCountCache(state, new GreedyPlanner(state), catalogue, 10, 1, compiler);
        assertNull(compiler.finish(cache, 10));
        assertEquals("profile_limit", compiler.disabledReason());
        assertEquals(catalogue.length, cache.samples().length);
    }

    @Test
    void maskedWorkspaceFallbackPreservesTheSuppliedSelectorAndDeclaredWidth() throws Exception {
        final var model = scalarModel(8, 6);
        final var state = new Engine(model);
        final var demand = new Request(3);
        final int[] calls = {0};
        try (final var scheduler = new MaskedFitCount(state, 44, new Request[]{demand}, 10, 1,
                (request, plans, count) -> {
                    calls[0]++;
                    return new int[]{count - 1};
                }, Integer.MAX_VALUE)) {
            assertEquals("false", scheduler.parameters().get("masked_enabled"));
            assertEquals("workspace_limit", scheduler.parameters().get("masked_disabled_reason"));
            state.schedule(scheduler, demand);
            assertEquals(2, state.placement(3).host());
            state.remove(demand);
            state.schedule(scheduler, demand);
            assertEquals(2, state.placement(3).host());
            assertEquals(2, calls[0]);
            assertEquals(1L, scheduler.counters().get("masked_workspace_limit"));
        }
        final var tooWide = new Engine(model);
        try (final var scheduler = new MaskedFitCount(tooWide, 44, new Request[]{demand}, 10, 1,
                (request, plans, count) -> new int[]{0, 1}, 1)) {
            assertThrows(IllegalArgumentException.class, () -> tooWide.schedule(scheduler, demand));
        }
    }

    @Test
    void randomShortlistOfOneCanChooseEitherHostDespiteFitCountPreference() throws Exception {
        final var model = scalarModel(8, 6);
        final var demand = new Request(3);
        final var catalogue = new Request[]{new Request(5)};
        final var chosenHosts = new HashSet<Integer>();
        for (int seed = 0; seed < 32; seed++) {
            final var state = new Engine(model);
            try (final var scheduler = new RandomNFitCount(state, seed, catalogue, 1)) {
                state.schedule(scheduler, demand);
                chosenHosts.add(state.placement(3).host());
            }
        }
        assertEquals(2, chosenHosts.size());
    }

    @Test
    void selectedPlansKeepTheirHostDuringFitCountScoring() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 5),
                alloc(3, new int[]{1}, 4), alloc(4, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 1),
                resource(4, 3, 2), resource(5, 4, 2));
        final var state = new Engine(model);
        try (final var scheduler = new FitCount(state, 0, new Request[]{new Request(4)}, 10, 1,
                (request, plans, count) -> new int[]{1, 2})) {
            state.schedule(scheduler, new Request(5));
            assertEquals(2, state.placement(5).host());
        }
    }

    @Test
    void selectorCanChooseTheLastCandidateAndRejectsInvalidIndices() throws Exception {
        final var model = scalarModel(8, 8);
        final var demand = new Request(3);
        final var state = new Engine(model);
        try (final var scheduler = new FitCount(state, 42, new Request[]{demand}, 10, 1,
                (request, plans, count) -> new int[]{count - 1})) {
            state.schedule(scheduler, demand);
            assertEquals(2, state.placement(3).host());
            state.remove(demand);
            state.schedule(scheduler, demand);
            assertEquals(2, state.placement(3).host());
        }
        final var selected = new Engine(model);
        final var chooser = new FitCount(selected, 42, new Request[]{demand}, 10, 1,
                (request, plans, count) -> new int[]{count - 1});
        final var wrapper = new SchedulingAlgorithm(selected) {
            @Override
            public void schedule(Request request) {
                final var direct = chooser.select(request);
                assertEquals(2, direct[0].host());
                assertNull(context.placement(request.resource(0)));
                final long revision = context.revision();
                final var candidates = chooser.selectCandidates(request, 2, 17);
                assertEquals(2, candidates.length);
                assertEquals(2, candidates[0][0].host());
                assertEquals(1, candidates[1][0].host());
                assertEquals(revision, context.revision());
                assertNull(context.placement(request.resource(0)));
                context.place(request, candidates[0]);
            }

            @Override
            public void capacityChanged(int host) {
                chooser.capacityChanged(host);
            }
        };
        for (int pass = 0; pass < 2; pass++) {
            selected.schedule(wrapper, demand);
            assertEquals(2, selected.placement(demand.resource(0)).host());
            selected.remove(demand);
        }
        for (final var indices : new int[][]{{}, {-1}, {2}, {1, 0}, {0, 0}}) {
            final var invalidState = new Engine(model);
            try (final var scheduler = new FitCount(invalidState, 42, new Request[]{demand}, 10, 1,
                    (request, plans, count) -> indices)) {
                assertThrows(IllegalArgumentException.class, () -> invalidState.schedule(scheduler, demand));
                assertNull(invalidState.placement(3));
            }
        }
    }

    @Test
    void randomShortlistKeepingAllCandidatesMatchesFitCountThroughRemoval() throws Exception {
        final var model = scalarModel(8, 6);
        final var units = new Request[]{new Request(3), new Request(4), new Request(5)};
        final var baseline = new Engine(model);
        final var shortlisted = new Engine(model);
        try (final var fitcount = new FitCount(baseline, 42, units, 10, 1);
                final var random = new RandomNFitCount(shortlisted, 42, units, 20, 10, 1)) {
            for (int pass = 0; pass < 2; pass++) {
                for (final var request : units) {
                    baseline.schedule(fitcount, request);
                    shortlisted.schedule(random, request);
                    assertEquals(baseline.placement(request.resource(0)).host(),
                            shortlisted.placement(request.resource(0)).host());
                }
                for (final var request : units) {
                    baseline.remove(request);
                    shortlisted.remove(request);
                }
            }
            assertEquals(fitcount.plannerCalls(), random.plannerCalls());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RandomNFitCount(shortlisted, 42, units, 0));
    }

    @Test
    void deviceModeMergesFloatExportAndCanonicalGpuShapes() throws Exception {
        for (final var mode : ClusterSpec.GpuMode.values()) {
            final var state = new Engine(model(mode,
                    alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .3), alloc(3, new int[]{11}, (double) .3f),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                    resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2)));
            final var cache = new FitCountCache(state, new GreedyPlanner(state),
                    new Request[]{new Request(2), new Request(3)}, 10, 1);
            cache.beginOrder();
            final var sample = cache.next();
            if (mode == ClusterSpec.GpuMode.DEVICES_20) {
                assertEquals(2, sample.frequency);
                assertNull(cache.next());
            } else {
                assertEquals(1, sample.frequency);
                assertEquals(1, cache.next().frequency);
                assertNull(cache.next());
            }
            final var population = new Request[]{new Request(2), new Request(3)};
            final var full = Catalogue.full(state.model(), population,
                    new Request[]{population[0], population[1], population[0]});
            final var weightedCache = new FitCountCache(state, new GreedyPlanner(state), full, 10, 1, null);
            assertEquals(mode == ClusterSpec.GpuMode.DEVICES_20 ? 1 : 2, weightedCache.samples().length);
            for (final var family : weightedCache.samples()) {
                assertEquals(mode == ClusterSpec.GpuMode.DEVICES_20 ? 2 : 1, family.frequency);
            }
        }
    }

    @Test
    void fullCatalogueWeightsCountPhysicalFamiliesOnceAndPreserveSelectedRequests() throws Exception {
        final var model = model(alloc(1, new int[]{1, 2}, 4, 5), alloc(2, new int[]{1, 2}, 3, 4),
                alloc(3, new int[]{1}, 4), alloc(4, new int[]{2}, 2), alloc(5, new int[]{1, 2}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2), resource(5, 5, 2));
        final var cpu = new Request(3);
        final var ram = new Request(4);
        final var population = new Request[102];
        Arrays.fill(population, cpu);
        population[100] = ram;
        population[101] = ram;
        final var selected = new Request[]{cpu, ram, ram};
        final var full = Catalogue.full(model, population, selected);
        selected[0] = ram;
        final var exposed = full.requests();
        exposed[0] = ram;
        assertTrue(cpu == full.request(0));
        assertEquals(100, full.frequency(0));
        assertEquals(2, full.frequency(1));
        assertEquals(2, full.frequency(2));
        assertEquals("102", full.parameters().get("catalogue_weight_mass"));
        assertEquals("2", full.parameters().get("catalogue_families"));
        assertEquals("3", full.parameters().get("catalogue_selected"));
        assertEquals("102", full.parameters().get("catalogue_population"));
        final var duplicatedCpu = Catalogue.full(model, population, new Request[]{cpu, cpu, ram});
        assertEquals(full.parameters(), duplicatedCpu.parameters());
        final var state = new Engine(model);
        final var cache = new FitCountCache(state, new GreedyPlanner(state), duplicatedCpu, 10, 1, null);
        assertEquals(100, cache.samples()[0].frequency);
        assertEquals(2, cache.samples()[1].frequency);
        assertEquals(full.parameters(), cache.parameters());
        assertThrows(IllegalArgumentException.class,
                () -> Catalogue.full(model, new Request[]{cpu}, new Request[]{ram}));
        final var ramHeavyPopulation = population.clone();
        Arrays.fill(ramHeavyPopulation, ram);
        ramHeavyPopulation[100] = cpu;
        ramHeavyPopulation[101] = cpu;
        final var ramHeavy = Catalogue.full(model, ramHeavyPopulation, full.requests());
        for (final boolean weighted : new boolean[]{false, true}) {
            final var legacy = new Engine(model);
            final var sampled = new Engine(model);
            final var complete = new Engine(model);
            final var entries = full.requests();
            final var completeCatalogue = weighted ? full : ramHeavy;
            try (final var old = weighted ? FitCount.weighted(legacy, 44, entries, 10, 1)
                    : new FitCount(legacy, 44, entries, 10, 1);
                    final var limited = weighted ? FitCount.weighted(sampled, 44, Catalogue.sampled(entries), 10, 1, 0, null)
                            : new FitCount(sampled, 44, Catalogue.sampled(entries), 10, 1, 0, null);
                    final var all = weighted ? FitCount.weighted(complete, 44, completeCatalogue, 10, 1, 0, null)
                            : new FitCount(complete, 44, completeCatalogue, 10, 1, 0, null)) {
                final var demand = new Request(5);
                legacy.schedule(old, demand);
                sampled.schedule(limited, demand);
                complete.schedule(all, demand);
                assertEquals(legacy.placement(5).host(), sampled.placement(5).host());
                assertEquals(weighted ? 1 : 2, sampled.placement(5).host());
                assertEquals(weighted ? 2 : 1, complete.placement(5).host());
                for (final var parameter : completeCatalogue.parameters().entrySet()) {
                    assertEquals(parameter.getValue(), all.parameters().get(parameter.getKey()));
                }
            }
        }
    }

    @Test
    void gpuCacheTracksPhysicalCopiesAcrossPlacementAndRemoval() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .6), alloc(3, new int[]{11}, .5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2));
        final var state = new Engine(model);
        final var sampleRequest = new Request(2);
        final var half = new Request(3);
        final var planner = new GreedyPlanner(state);
        final var cache = new FitCountCache(state, planner, new Request[]{sampleRequest, sampleRequest}, 10, 1);
        cache.beginOrder();
        final var sample = cache.next();
        assertEquals(2, sample.frequency);
        assertEquals(2, cache.count(sample, 1));
        assertEquals(2, cache.total(sample));
        try (final var scheduler = new FitCount(state, 42, new Request[]{sampleRequest})) {
            state.schedule(scheduler, half);
            cache.changed(1);
            assertEquals(1, cache.count(sample, 1));
            assertEquals(1, cache.total(sample));
            assertEquals(1, planner.fitCount(sampleRequest, 1, 10));
            state.schedule(scheduler, sampleRequest);
            cache.changed(1);
            assertEquals(0, cache.count(sample, 1));
            assertEquals(0, cache.total(sample));
            assertTrue(state.placement(2).gpus()[0] != state.placement(3).gpus()[0]);
            state.remove(half);
            cache.changed(1);
            assertEquals(1, cache.count(sample, 1));
            assertEquals(planner.fitCount(sampleRequest, 1, 10), cache.count(sample, 1));
            state.remove(sampleRequest);
            cache.changed(1);
            assertEquals(2, cache.count(sample, 1));
            assertEquals(2, cache.total(sample));
        }
    }

    @Test
    void equivalentInternalNumaLinksShareOnePhysicalShape() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 2, 3), alloc(2, new int[]{1, 2}, 1, 2),
                alloc(3, new int[]{1, 2}, 1, 1), alloc(4, new int[]{1, 2}, .5, .5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setTarget(6).setDomain(2))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)),
                rules(Constraints.newBuilder().setId(6).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setTarget(7).setDomain(2))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 4, 4), resource(5, 2, 5), resource(6, 4, 5),
                resource(7, 4, 5), resource(8, 2, 5), resource(9, 4, 6));
        final var state = new Engine(model);
        final var planner = new GreedyPlanner(state);
        final var first = new Request(4, 5, 6);
        final var second = new Request(7, 8, 9);
        assertEquals(0, planner.fitCount(first, 1, 10));
        assertEquals(0, planner.fitCount(second, 1, 10));
        final var cache = new FitCountCache(state, planner,
                new Request[]{first, second, new Request(7, 8, 6)}, 10, 1);
        cache.beginOrder();
        final var sample = cache.next();
        assertEquals(2, sample.frequency);
        assertEquals(planner.fitCount(first, 1, 10), cache.count(sample, 1));
        assertEquals(planner.fitCount(second, 1, 10), cache.count(sample, 1));
        assertEquals(1, cache.cachedShapes());
        assertEquals(1, cache.next().frequency);
        assertNull(cache.next());
    }

    @Test
    void candidateSelectionComparesEveryNumaAnchor() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 13, 13), alloc(2, new int[]{1, 2}, 8, 5),
                alloc(3, new int[]{1, 2}, 5, 8), alloc(4, new int[]{1, 2}, 4, 1),
                alloc(5, new int[]{1, 2}, 1, 4), alloc(6, new int[]{1, 2}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 4, 4), resource(5, 5, 4), resource(6, 6, 4));
        final var first = new Request(4);
        final var second = new Request(5);
        final var demand = new Request(6);
        for (final boolean repeated : new boolean[]{false, true}) {
            final var state = new Engine(model);
            final var samples = repeated ? new Request[]{first, second, second}
                    : new Request[]{first, second};
            try (final var scheduler = new FitCount(state, 42, samples)) {
                state.schedule(scheduler, demand);
                assertEquals(repeated ? 2 : 3, state.placement(6).numa());
            }
        }
    }

    @Test
    void frequencyOrdersLexicographicLossAndEvictionDoesNotChangeTheWinner() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 8, 5), alloc(2, new int[]{1, 2}, 5, 8),
                alloc(3, new int[]{1, 2}, 4, 1), alloc(4, new int[]{1, 2}, 1, 4),
                alloc(5, new int[]{1, 2}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2), resource(5, 5, 2));
        final var first = new Request(3);
        final var second = new Request(4);
        final var demand = new Request(5);
        for (final int cached : new int[]{1, 16}) {
            for (final boolean repeated : new boolean[]{false, true}) {
                final var state = new Engine(model);
                final var samples = repeated ? new Request[]{first, second, second}
                        : new Request[]{first, second};
                try (final var scheduler = new FitCount(state, 42, samples, 10, cached)) {
                    state.schedule(scheduler, demand);
                    assertEquals(repeated ? 1 : 2, state.placement(5).host());
                    state.remove(demand);
                    state.schedule(scheduler, demand);
                    assertEquals(repeated ? 1 : 2, state.placement(5).host());
                }
            }
        }
    }

    @Test
    void weightedLossTradesRareCapacityForCommonCapacity() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 4, 5), alloc(2, new int[]{1, 2}, 3, 4),
                alloc(3, new int[]{1}, 4), alloc(4, new int[]{2}, 2),
                alloc(5, new int[]{1, 2}, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2), resource(5, 5, 2));
        final var cpu = new Request(3);
        final var ram = new Request(4);
        final var demand = new Request(5);
        final var ramHeavy = new Request[]{cpu, ram, ram};
        final var cpuHeavy = new Request[]{cpu, cpu, ram};
        final var baseline = new Engine(model);
        try (final var scheduler = new FitCount(baseline, 42, ramHeavy)) {
            baseline.schedule(scheduler, demand);
            assertEquals(2, baseline.placement(5).host());
        }
        // Host 1 loses one CPU copy, host 2 loses one RAM copy. At cap 1 the RAM loss disappears.
        for (final int cached : new int[]{1, 16}) {
            for (final int cap : new int[]{1, 10}) {
                for (final var catalogue : new Request[][]{ramHeavy, cpuHeavy}) {
                    final var state = new Engine(model);
                    final int expected = cap == 10 && catalogue == ramHeavy ? 1 : 2;
                    try (final var scheduler = FitCount.weighted(state, 42, catalogue, cap, cached)) {
                        state.schedule(scheduler, demand);
                        assertEquals(expected, state.placement(5).host());
                        state.remove(demand);
                        state.schedule(scheduler, demand);
                        assertEquals(expected, state.placement(5).host());
                    }
                }
            }
        }
    }

    @Test
    void weightedLossPreservesGainsFromGreedyNumaPacking() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 7, 2), alloc(2, new int[]{1, 2}, 4, 1),
                alloc(3, new int[]{1, 2}, 3, 1), alloc(4, new int[]{1, 2}, 2, 1),
                alloc(5, new int[]{1}, 4), alloc(6, new int[]{2}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 4, 4), resource(5, 5, 4), resource(6, 6, 4));
        final var state = new Engine(model);
        final var sample = new Request(4, 5);
        final var demand = new Request(6);
        // Filling RAM on NUMA 2 (resource ID) sends the first sample member to NUMA 3,
        // leaving all four CPUs on NUMA 2 for the second member. Seed 0 breaks a tie toward NUMA 3.
        try (final var scheduler = FitCount.weighted(state, 0, new Request[]{sample}, 10, 16)) {
            state.schedule(scheduler, demand);
            assertEquals(2, state.placement(6).numa());
        }
    }

    @Test
    void totalFitLimitUsesFullSupplyAndCurrentOccupancyForLexAndMean() throws Exception {
        final var model = model(alloc(1, new int[]{1, 2}, 10, 10),
                alloc(2, new int[]{1, 2}, 5, 6), alloc(3, new int[]{1}, 3),
                alloc(4, new int[]{1, 2}, 2, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2),
                resource(4, 3, 2), resource(5, 4, 2));
        final var background = new Request(3);
        final var sample = new Request(4);
        final var demand = new Request(5);
        for (final boolean weighted : new boolean[]{false, true}) {
            for (final int[] settings : new int[][]{{1, 1, 2}, {2, 1, 1}, {1, 4, 1}, {1, 0, 1}}) {
                final var state = new Engine(model);
                final var catalogue = new Request[settings[0]];
                Arrays.fill(catalogue, sample);
                final FitCount[] delegate = {null};
                final var scheduler = new SchedulingAlgorithm(state) {
                    @Override
                    public void schedule(Request request) {
                        if (request == background) {
                            context.place(request, new GreedyPlanner(context).plan(request, 1, 0));
                        } else {
                            if (delegate[0] == null) {
                                delegate[0] = weighted ? FitCount.weighted(context, 0, catalogue, 10, 1, settings[1], null)
                                        : new FitCount(context, 0, catalogue, 10, 1, settings[1], null);
                            }
                            delegate[0].schedule(request);
                        }
                    }

                    @Override
                    public void capacityChanged(int host) {
                        if (delegate[0] != null) {
                            delegate[0].capacityChanged(host);
                        }
                    }
                };
                state.schedule(scheduler, background);
                state.schedule(scheduler, demand);
                assertEquals(settings[2], state.placement(5).host());
                state.remove(demand);
                state.remove(background);
                final var random = new SplittableRandom(0);
                if (settings[2] == 2) {
                    random.nextInt(2);
                }
                state.schedule(scheduler, demand);
                assertEquals(1 + random.nextInt(2), state.placement(5).host());
                delegate[0].close();
            }
        }
    }

    @Test
    void physicalCacheIgnoresReplicaLimitsAndRefreshesAfterPlacementAndRemoval() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 10), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(1).setMax(1).setHard(true))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2).setPinnedHost(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3));
        final var state = new Engine(model);
        final var first = new Request(2);
        final var second = new Request(3);
        final var planner = new GreedyPlanner(state);
        final var cache = new FitCountCache(state, planner, new Request[]{first, second}, 10, 1);
        cache.beginOrder();
        final var sample = cache.next();
        assertEquals(2, sample.frequency);
        assertNull(cache.next());
        assertEquals(5, cache.count(sample, 1));
        assertEquals(5, cache.total(sample));
        try (final var scheduler = new FitCount(state, 42, new Request[]{first, second})) {
            state.schedule(scheduler, first);
            cache.changed(1);
            assertEquals(4, cache.count(sample, 1));
            assertEquals(4, cache.total(sample));
            state.schedule(scheduler, second);
            cache.changed(1);
            assertEquals(3, cache.count(sample, 1));
            state.remove(first);
            cache.changed(1);
            assertEquals(4, cache.count(sample, 1));
            state.remove(second);
            cache.changed(1);
            assertEquals(5, cache.count(sample, 1));
        }
    }

    @Test
    void boundedRowsMatchRecomputationAcrossHostChanges() throws Exception {
        final var model = scalarModel(8, 6);
        final var state = new Engine(model);
        final var units = new Request[]{new Request(3), new Request(4), new Request(5)};
        final var planner = new GreedyPlanner(state);
        final var cache = new FitCountCache(state, planner, units, 7, 1);
        final var delegate = new FitCount(state, 3, units, 7, 1);
        try (delegate; final var scheduler = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                delegate.schedule(request);
            }

            @Override
            public void capacityChanged(int host) {
                delegate.capacityChanged(host);
                cache.changed(host);
            }
        }) {
            for (int step = 0; step < units.length * 2 + 1; step++) {
                if (step != 0) {
                    final var unit = units[(step - 1) % units.length];
                    if (step <= units.length) {
                        state.schedule(scheduler, unit);
                    } else {
                        state.remove(unit);
                    }
                }
                assertEquals(step, cache.generation());
                for (final double limit : new double[]{0, 1.5, 3.5, Double.MAX_VALUE}) {
                    int expected = 0;
                    for (final var sample : cache.samples()) {
                        long total = 0;
                        for (int host = 1; host <= model.hostCount; host++) {
                            total += planner.fitCount(sample.request, host, 7);
                        }
                        if (total / (double) sample.frequency <= limit) {
                            expected++;
                        }
                    }
                    cache.beginOrder(limit);
                    double last = -1;
                    int seen = 0;
                    for (var sample = cache.next(); sample != null; sample = cache.next()) {
                        long total = 0;
                        for (int host = 1; host <= model.hostCount; host++) {
                            final int count = planner.fitCount(sample.request, host, 7);
                            assertEquals(count, cache.count(sample, host));
                            total += count;
                        }
                        assertEquals(total, cache.total(sample));
                        assertTrue(sample.weightedTotal() >= last);
                        assertTrue(sample.weightedTotal() <= limit);
                        last = sample.weightedTotal();
                        assertTrue(cache.cachedShapes() <= 1);
                        seen++;
                    }
                    assertEquals(expected, seen);
                }
            }
        }
    }

    @Test
    void totalFitCutoffIncludesFractionalFrequencyBoundary() throws Exception {
        final var state = new Engine(scalarModel(8, 6));
        final var sample = new Request(5);
        final var cache = new FitCountCache(state, new GreedyPlanner(state), new Request[]{sample, sample, sample}, 10, 1);
        cache.beginOrder(4.0 / 3);
        assertEquals(3, cache.next().frequency);
        assertNull(cache.next());
        cache.beginOrder(Math.nextDown(4.0 / 3));
        assertNull(cache.next());
    }

    @Test
    void zeroDemandStopsAtTheCapAndDiskGeometryRemainsInTheShapeKey() throws Exception {
        final var disk = DiskPlan.newBuilder().setMedia(Media.HDD).setExtents(1)
                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1);
        final var model = model(alloc(1, new int[]{1}, 10),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setFlags(2)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD).addDisks(disk)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk.clone().setExtents(2))),
                resource(1, 1, 1), resource(2, 0, 2), resource(3, 0, 3), resource(4, 0, 4));
        final var state = new Engine(model);
        final var planner = new GreedyPlanner(state);
        final var zero = new Request(2);
        assertEquals(9, planner.fitCount(zero, 1, 9));
        final var cache = new FitCountCache(state, planner,
                new Request[]{zero, new Request(3), new Request(4)}, 9, 1);
        cache.beginOrder();
        int samples = 0;
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            assertEquals(1, sample.frequency);
            samples++;
        }
        assertEquals(3, samples);
        final var full = Catalogue.full(model,
                new Request[]{zero, new Request(3), new Request(4), new Request(3)},
                new Request[]{new Request(3), new Request(4)});
        final var weightedCache = new FitCountCache(state, planner, full, 9, 1, null);
        assertEquals(2, weightedCache.samples().length);
        assertEquals(2, weightedCache.samples()[0].frequency);
        assertEquals(1, weightedCache.samples()[1].frequency);
        assertEquals("3", full.parameters().get("catalogue_weight_mass"));
        assertThrows(IllegalArgumentException.class, () -> new FitCount(state, 0, new Request[]{zero}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FitCount(state, 0, new Request[]{zero}, 9, 0));
        for (final double limit : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FitCount(state, 0, new Request[]{zero}, 9, 1, limit, null));
        }
        try (final var scheduler = new FitCount(state, 42, new Request[]{zero})) {
            state.schedule(scheduler, zero);
            assertEquals(1, state.placement(2).host());
        }
    }

    @Test
    void seededTieBreakingAndTheDefaultBatchLoopAreRepeatable() throws Exception {
        final var model = scalarModel(8, 8);
        final var units = new Request[]{new Request(3), new Request(4), new Request(5)};
        final int[][] placements = new int[2][];
        for (int trial = 0; trial < placements.length; trial++) {
            final var state = new Engine(model);
            try (final var scheduler = new FitCount(state, 42, new Request[0])) {
                state.schedule(scheduler, units);
                placements[trial] = new int[units.length];
                for (int index = 0; index < units.length; index++) {
                    placements[trial][index] = state.placement(units[index].resource(0)).host();
                }
            }
        }
        assertArrayEquals(placements[0], placements[1]);
    }

    @Test
    void detachedSelectionPreservesTheBaselineAndReturnsDistinctOwnedAlternatives() throws Exception {
        final var model = model(alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 1, 1),
                resource(4, 2, 2), resource(5, 2, 2));
        final var requests = new Request[]{new Request(4), new Request(5)};
        for (final int limit : new int[]{1, 2, 8}) {
            for (final long candidateSeed : new long[]{1, 42, -1}) {
                final var baseline = new Engine(model);
                final var selected = new Engine(model);
                try (final var ordinary = new FitCount(baseline, 42, requests);
                        final var chooser = new FitCount(selected, 42, requests)) {
                    final var owned = new ArrayList<Assignment[]>();
                    final var wrapper = new SchedulingAlgorithm(selected) {
                        @Override
                        public void schedule(Request request) {
                            final long revision = context.revision();
                            final var candidates = chooser.selectCandidates(request, limit, candidateSeed);
                            assertEquals(revision, context.revision());
                            assertEquals(Math.min(limit, 3), candidates.length);
                            final var hosts = new HashSet<Integer>();
                            for (final var plans : candidates) {
                                assertEquals(request.resource(0), plans[0].resource());
                                assertTrue(hosts.add(plans[0].host()));
                                assertTrue(context.canPlace(request, plans));
                            }
                            owned.add(candidates[0]);
                            context.place(request, candidates[0]);
                        }

                        @Override
                        public void capacityChanged(int host) {
                            chooser.capacityChanged(host);
                        }
                    };
                    for (int pass = 0; pass < 2; pass++) {
                        for (final var request : requests) {
                            baseline.schedule(ordinary, request);
                            selected.schedule(wrapper, request);
                            final var expected = baseline.placement(request.resource(0));
                            final var actual = selected.placement(request.resource(0));
                            assertEquals(expected.host(), actual.host());
                            assertEquals(expected.numa(), actual.numa());
                            assertArrayEquals(expected.devices(), actual.devices());
                        }
                        for (int index = requests.length - 1; index >= 0; index--) {
                            baseline.remove(requests[index]);
                            selected.remove(requests[index]);
                        }
                    }
                    assertEquals(4, owned.size());
                    assertEquals(4, owned.getFirst()[0].resource());
                    assertEquals(1, owned.getFirst()[0].diskOffsets().length);
                    assertEquals(0, owned.getFirst()[0].diskOffsets()[0]);
                    assertThrows(IllegalArgumentException.class,
                            () -> chooser.selectCandidates(requests[0], 0, candidateSeed));
                }
            }
        }
    }

    private static ClusterSpec scalarModel(double first, double second) throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, first));
        records.add(alloc(2, new int[]{1}, second));
        for (int amount = 1; amount <= 3; amount++) {
            records.add(alloc(amount + 2, new int[]{1}, amount));
        }
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        records.add(resource(1, 1, 1));
        records.add(resource(2, 2, 1));
        for (int index = 3; index <= 5; index++) {
            records.add(resource(index, index, 2));
        }
        return model(records.toArray(Record[]::new));
    }

    @Test
    void enabledPortfolioMatchesIndependentUnionOracleWithoutMutatingAtomicGeometry() throws Exception {
        final boolean[] overlapWithoutRefill = {false};
        for (final var mode : ClusterSpec.GpuMode.values()) {
            final var model = portfolioGeometryModel(mode);
            for (final int pod : new int[]{17, 21}) {
                for (final boolean tied : new boolean[]{false, true}) {
                    final var request = new Request(pod, 18);
                    final var catalogue = tied ? new Request[0] : new Request[]{new Request(19), new Request(20)};
                    final var state = new Engine(model);
                    state.schedule(new SchedulingAlgorithm(state) {
                        @Override
                        public void schedule(Request pending) throws Exception {
                            final var planner = new GreedyPlanner(state);
                            final var collected = new ArrayList<Assignment[]>();
                            for (int host = 1; host <= model.hostCount; host++) {
                                for (final int numa : planner.numaNodes(host)) {
                                    final var plan = planner.plan(pending, host, numa);
                                    if (plan != null) {
                                        collected.add(plan);
                                    }
                                }
                            }
                            final int count = collected.size();
                            assertEquals(8, count);
                            final var plans = collected.toArray(new Assignment[count + 1][]);
                            final var before = new Assignment[count][];
                            final var original = new Assignment[count][];
                            final var fragmentation = model.gpus == null
                                    ? planner.cpuRamFragmentationScorer(catalogue) : planner.gpuFragmentationScorer(catalogue);
                            final var least = KubernetesResourceScoring.scorer(planner, Strategy.LEAST_ALLOCATED);
                            final double[] fragmentScores = new double[count];
                            final double[] leastScores = new double[count];
                            for (int index = 0; index < count; index++) {
                                original[index] = plans[index].clone();
                                before[index] = Arrays.stream(plans[index]).map(Assignment::copy).toArray(Assignment[]::new);
                                assertTrue(plans[index][0].numa() > 0);
                                assertTrue(plans[index][1].devices().length > 0);
                                if (mode == ClusterSpec.GpuMode.DEVICES_20 && pod == 17) {
                                    assertTrue(plans[index][0].gpus().length > 0);
                                }
                                fragmentScores[index] = fragmentation.applyAsDouble(plans[index]);
                                leastScores[index] = least.applyAsDouble(plans[index]);
                                assertTrue(Double.isFinite(fragmentScores[index]));
                                assertTrue(Double.isFinite(leastScores[index]));
                            }
                            for (final int[] quotas : new int[][]{{2, 4, 4}, {2, 3, 0}, {2, 0, 3}}) {
                                for (final long seed : new long[]{0, 44, -7}) {
                                    final var random = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
                                    try (final var portfolio = new PortfolioFitCount(state, seed, catalogue,
                                            quotas[0], quotas[1], quotas[2], 10, 1)) {
                                        long totalOverlap = 0;
                                        long totalRetained = 0;
                                        for (int decision = 0; decision < 2; decision++) {
                                            final int[] expected = portfolioOracle(seed, 0, random, quotas,
                                                    fragmentScores, leastScores);
                                            final int[] actual = portfolio.select(pending, plans, count);
                                            assertArrayEquals(expected, actual);
                                            final int overlap = Arrays.stream(quotas).map(value -> Math.min(value, count)).sum()
                                                    - expected.length;
                                            totalOverlap += overlap;
                                            totalRetained += expected.length;
                                            overlapWithoutRefill[0] |= overlap > 0 && expected.length
                                                    < Math.min(count, Arrays.stream(quotas).sum());
                                        }
                                        assertEquals(totalOverlap, portfolio.counters().get("shortlist_overlap"));
                                        assertEquals(totalRetained, portfolio.counters().get("retained_plans"));
                                        assertEquals(quotas[1] == 0 ? 0L : 2L * count,
                                                portfolio.counters().get("fragmentation_scores"));
                                        assertEquals(quotas[2] == 0 ? 0L : 2L * count,
                                                portfolio.counters().get("least_scores"));
                                        assertEquals(model.gpus == null && quotas[1] > 0 ? 2L : 0L,
                                                portfolio.counters().get("fragmentation_cpu_ram_decisions"));
                                    }
                                }
                            }
                            for (int index = 0; index < count; index++) {
                                assertTrue(collected.get(index) == plans[index]);
                                for (int member = 0; member < pending.size(); member++) {
                                    assertTrue(original[index][member] == plans[index][member]);
                                    assertPortfolioGeometry(before[index][member], plans[index][member]);
                                }
                            }
                            assertNull(plans[count]);
                            assertEquals(0, state.revision());
                        }
                    }, request);
                }
            }
            final var probe = new Engine(model);
            final var request = new Request(17, 18);
            final Assignment[][] expected = new Assignment[1][];
            probe.schedule(new SchedulingAlgorithm(probe) {
                @Override
                public void schedule(Request pending) {
                    final var planner = new GreedyPlanner(probe);
                    final var plans = new ArrayList<Assignment[]>();
                    for (int host = 1; host <= model.hostCount; host++) {
                        for (final int numa : planner.numaNodes(host)) {
                            final var plan = planner.plan(pending, host, numa);
                            if (plan != null) {
                                plans.add(plan);
                            }
                        }
                    }
                    final var least = KubernetesResourceScoring.scorer(planner, Strategy.LEAST_ALLOCATED);
                    final double[] scores = plans.stream().mapToDouble(least::applyAsDouble).toArray();
                    final int[] shortlist = portfolioOracle(44, 2,
                            new SplittableRandom(44 ^ 0x6A09E667F3BCC909L), new int[]{2, 4, 4},
                            new double[plans.size()], scores);
                    expected[0] = plans.get(shortlist[new SplittableRandom(44).nextInt(shortlist.length)]);
                }
            }, request);
            final var actual = new Engine(model);
            try (final var portfolio = new PortfolioFitCount(actual, 44, new Request[0], 2, 4, 4, 10, 1)) {
                actual.schedule(portfolio, new Request(22));
                actual.schedule(portfolio, new Request(22));
                assertNull(actual.placement(22));
                actual.schedule(portfolio, request);
                for (int member = 0; member < request.size(); member++) {
                    assertPortfolioGeometry(expected[0][member], actual.placement(request.resource(member)));
                }
            }
        }
        assertTrue(overlapWithoutRefill[0], "Overlapping sources must retain fewer plans than the available quota");
    }

    private static int[] portfolioOracle(long seed, long attempt, SplittableRandom random, int[] quotas,
            double[] fragmentation, double[] least) {
        return portfolioOracle(seed, attempt, random, quotas, fragmentation, least, null);
    }

    private static int[] portfolioOracle(long seed, long attempt, SplittableRandom random, int[] quotas,
            double[] fragmentation, double[] least, double[] geometric) {
        final int count = fragmentation.length;
        final int[] pool = java.util.stream.IntStream.range(0, count).toArray();
        final var union = new TreeSet<Integer>();
        int remaining = count;
        for (int index = 0; index < Math.min(quotas[0], count); index++) {
            final int chosen = count <= quotas[0] ? index : random.nextInt(remaining);
            union.add(pool[chosen]);
            if (count > quotas[0]) {
                pool[chosen] = pool[--remaining];
            }
        }
        for (int source = 1; source < quotas.length; source++) {
            final boolean descending = source == 2;
            final double[] scores = source == 1 ? fragmentation : source == 2 ? least : geometric;
            final long salt = source == 1 ? 0x3C6EF372FE94F82BL
                    : source == 2 ? 0xA54FF53A5F1D36F1L : 0x510E527FADE682D1L;
            final Integer[] ranked = java.util.stream.IntStream.range(0, count).boxed().toArray(Integer[]::new);
            Arrays.sort(ranked, (first, second) -> {
                final int score = scores[first] < scores[second] ? -1 : scores[first] > scores[second] ? 1 : 0;
                if (score != 0) {
                    return descending ? -score : score;
                }
                final long firstKey = portfolioMix(seed ^ portfolioMix(attempt) ^ portfolioMix(first) ^ salt);
                final long secondKey = portfolioMix(seed ^ portfolioMix(attempt) ^ portfolioMix(second) ^ salt);
                final int key = Long.compareUnsigned(firstKey, secondKey);
                return key == 0 ? Integer.compare(first, second) : key;
            });
            for (int index = 0; index < Math.min(quotas[source], count); index++) {
                union.add(ranked[index]);
            }
        }
        return union.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void meanHybridsMatchIndependentShortlistAndWeightedLossOracles() throws Exception {
        final int hosts = 32;
        final var records = new ArrayList<Record>();
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        for (int host = 1; host <= hosts; host++) {
            records.add(alloc(host, new int[]{1, 2}, 12 + host % 7 * 3, 20 + host % 5 * 5));
            records.add(resource(host, host, 1));
        }
        records.add(alloc(hosts + 1, new int[]{1, 2}, 3, 5));
        records.add(alloc(hosts + 2, new int[]{1, 2}, 8, 1));
        records.add(alloc(hosts + 3, new int[]{1, 2}, 1, 8));
        for (int index = 1; index <= 3; index++) {
            records.add(resource(hosts + index, hosts + index, 2));
        }
        final var model = model(records.toArray(Record[]::new));
        final var request = new Request(hosts + 1);
        final var catalogue = new Request[]{new Request(hosts + 2), new Request(hosts + 3), new Request(hosts + 3)};
        final var oracleState = new Engine(model);
        final var planner = new GreedyPlanner(oracleState);
        final var plans = new Assignment[hosts][];
        final var original = new Assignment[hosts][];
        oracleState.schedule(new SchedulingAlgorithm(oracleState) {
            @Override
            public void schedule(Request pending) {
                for (int host = 1; host <= hosts; host++) {
                    plans[host - 1] = planner.plan(pending, host, 0);
                    original[host - 1] = Arrays.stream(plans[host - 1]).map(Assignment::copy).toArray(Assignment[]::new);
                }
            }
        }, request);
        final var fragmentation = planner.cpuRamFragmentationScorer(catalogue);
        final var least = KubernetesResourceScoring.scorer(planner, Strategy.LEAST_ALLOCATED);
        final var geometric = planner.normalizedResidualL2Scorer();
        final double[] fragments = Arrays.stream(plans).mapToDouble(fragmentation::applyAsDouble).toArray();
        final double[] leastScores = Arrays.stream(plans).mapToDouble(least::applyAsDouble).toArray();
        final double[] geometry = Arrays.stream(plans).mapToDouble(geometric::applyAsDouble).toArray();
        final String[] ids = {"randomn-mean-fitcount", "portfolio-mean-fitcount", "geometric-portfolio-mean-fitcount"};
        final int[][] quotas = {{28, 0, 0}, {20, 4, 4}, {20, 0, 4, 4}};
        for (int variant = 0; variant < ids.length; variant++) {
            for (final long seed : new long[]{44, 45}) {
                final int[] selected = portfolioOracle(seed, 0,
                        new SplittableRandom(seed ^ 0x6A09E667F3BCC909L), quotas[variant], fragments, leastScores, geometry);
                final var tied = new ArrayList<Integer>();
                long best = Long.MAX_VALUE;
                for (final int index : selected) {
                    long loss = 0;
                    for (final var sample : catalogue) {
                        loss += planner.fitCount(sample, index + 1, 10)
                                - planner.fitCount(sample, index + 1, plans[index], 10);
                    }
                    if (loss < best) {
                        tied.clear();
                        best = loss;
                    }
                    if (loss == best) {
                        tied.add(index);
                    }
                }
                final int expected = tied.get(tied.size() == 1 ? 0 : new SplittableRandom(seed).nextInt(tied.size()));
                for (final int cacheRows : new int[]{1, 64}) {
                    for (final double limit : new double[]{0, 4}) {
                        final var state = new Engine(model);
                        final var settings = new AlgorithmRegistry.Settings(ids[variant], Map.of(
                                "seed", new JsonPrimitive(seed), "cacheRows", new JsonPrimitive(cacheRows),
                                "maxTotalFit", new JsonPrimitive(limit)));
                        try (final var algorithm = AlgorithmRegistry.create(settings, state, catalogue)) {
                            state.schedule(algorithm, request);
                            assertPortfolioGeometry(plans[expected][0], state.placement(request.resource(0)));
                            if (variant != 0) {
                                assertEquals((long) selected.length, algorithm.counters().get("retained_plans"));
                                assertEquals(variant == 2 ? (long) hosts : 0L, algorithm.counters().get("geometric_scores"));
                            }
                        }
                    }
                }
            }
        }
        for (int index = 0; index < plans.length; index++) {
            assertPortfolioGeometry(original[index][0], plans[index][0]);
        }
        assertEquals(0, oracleState.revision());
    }

    private static long portfolioMix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static void assertPortfolioGeometry(Assignment expected, Assignment actual) {
        assertEquals(expected.resource(), actual.resource());
        assertEquals(expected.host(), actual.host());
        assertEquals(expected.numa(), actual.numa());
        assertArrayEquals(expected.diskOffsets(), actual.diskOffsets());
        assertArrayEquals(expected.devices(), actual.devices());
        assertArrayEquals(expected.gpus(), actual.gpus());
    }

    private static ClusterSpec portfolioGeometryModel(ClusterSpec.GpuMode mode) throws Exception {
        final var records = new ArrayList<Record>();
        final int[][] capacity = {{8, 8, 2}, {6, 10, 2}, {10, 6, 1}, {8, 8, 2}};
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        for (int host = 1; host <= 4; host++) {
            final var values = capacity[host - 1];
            records.add(alloc(host, new int[]{1, 2, 11}, values[0], values[1], values[2]));
            records.add(alloc(4 + host, new int[]{1, 2}, values[0] / 2.0, values[1] / 2.0));
            records.add(resource(host, host, 1));
            for (int ordinal = 1; ordinal <= 2; ordinal++) {
                final int rule = 2 * host + ordinal - 1;
                records.add(rules(Constraints.newBuilder().setId(rule).setKind(Kind.NUMA)
                        .setParent(host).setOrdinal(ordinal)));
                records.add(resource(rule + 3, 4 + host, rule));
            }
            records.add(rules(Constraints.newBuilder().setId(9 + host).setKind(Kind.DISK)
                    .setParent(host).setMedia(Media.HDD)));
            records.add(resource(12 + host, 9, 9 + host));
        }
        records.add(alloc(9, new int[]{18, 19, 20}, 12, 1, 200));
        records.add(alloc(10, new int[]{1, 2, 11}, 1, 1, .25));
        records.add(alloc(11, new int[]{1, 2, 11}, 6, 1, .8));
        records.add(alloc(12, new int[]{1, 2, 11}, 3, 2, 1));
        records.add(alloc(13, new int[]{1, 2}, 1, 1));
        records.add(alloc(14, new int[]{1}, 100));
        records.add(rules(Constraints.newBuilder().setId(14).setKind(Kind.POD)
                .addLinks(Link.newBuilder().setTarget(18).setDomain(1))));
        records.add(rules(Constraints.newBuilder().setId(15).setKind(Kind.SHARD).setGroup(1)
                .addDisks(DiskPlan.newBuilder().setMedia(Media.HDD).setExtents(2).setSpindles(.1).setIops(20)
                        .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1))));
        records.add(rules(Constraints.newBuilder().setId(16).setKind(Kind.POD)));
        records.add(rules(Constraints.newBuilder().setId(17).setKind(Kind.POD)));
        records.add(resource(17, 10, 14));
        records.add(resource(18, 0, 15));
        records.add(resource(19, 11, 16));
        records.add(resource(20, 12, 16));
        records.add(resource(21, 13, 14));
        records.add(resource(22, 14, 17));
        return model(mode, records.toArray(Record[]::new));
    }
}
