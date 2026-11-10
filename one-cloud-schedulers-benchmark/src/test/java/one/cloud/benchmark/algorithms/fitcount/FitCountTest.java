package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.GreedyPlanner;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitCountTest {
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
        try (final var scheduler = new FitCount(state, 3, units, 7, 1)) {
            for (int step = 0; step < units.length * 2 + 1; step++) {
                if (step != 0) {
                    final var unit = units[(step - 1) % units.length];
                    if (step <= units.length) {
                        state.schedule(scheduler, unit);
                        cache.changed(state.placement(unit.resource(0)).host());
                    } else {
                        final int host = state.placement(unit.resource(0)).host();
                        state.remove(unit);
                        cache.changed(host);
                    }
                }
                cache.beginOrder();
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
                    last = sample.weightedTotal();
                    assertTrue(cache.cachedShapes() <= 1);
                    seen++;
                }
                assertEquals(units.length, seen);
            }
        }
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
        assertThrows(IllegalArgumentException.class, () -> new FitCount(state, 0, new Request[]{zero}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FitCount(state, 0, new Request[]{zero}, 9, 0));
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
}
