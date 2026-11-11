package one.cloud.benchmark.algorithms.fgd;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FgdTest {
    @Test
    void cpuRamScoreDistinguishesHostsWithIdenticalWholeCopyCounts() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1, 2}, 7, 11), alloc(2, new int[]{1, 2}, 6, 13),
                alloc(3, new int[]{1, 2}, 2, 2), alloc(4, new int[]{1, 2}, 4, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2)));
        final var request = new Request(3);
        final var profile = new Request(4);
        final var planner = new GreedyPlanner(state);
        for (int host = 1; host <= 2; host++) {
            assertEquals(1, planner.fitCount(profile, host, 10));
            assertEquals(1, planner.fitCount(profile, host, scalarPlan(3, host), 10));
        }
        // Seed 2 would select host 2 if both scores tied.
        try (final var algorithm = Fgd.cpuRam(state, 2, new Request[]{profile})) {
            state.schedule(algorithm, request);
            assertEquals(1, state.placement(3).host());
            state.remove(request);
            state.schedule(algorithm, request);
            assertEquals(1, state.placement(3).host());
        }
    }

    @Test
    void cpuRamScoreComparesDeltasEvenForTinyReferenceDemands() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1, 2}, 8, 2), alloc(2, new int[]{1, 2}, 4, 8),
                alloc(3, new int[]{1, 2}, 2, 1), alloc(4, new int[]{1, 2}, 1e-320, 2e-320),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2)));
        final var planner = new GreedyPlanner(state);
        final var score = planner.cpuRamFragmentationScorer(new Request[]{new Request(4)});
        assertEquals(-.1875, score.applyAsDouble(scalarPlan(3, 1)), 1e-12);
        assertEquals(.375, score.applyAsDouble(scalarPlan(3, 2)), 1e-12);
    }

    @Test
    void repeatedCpuRamProfilesChangeThePreferredHost() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 4, 6), alloc(2, new int[]{1, 2}, 15, 24),
                alloc(3, new int[]{1, 2}, 2, 2), alloc(4, new int[]{1, 2}, 3.2, 5),
                alloc(5, new int[]{1, 2}, 2.2, 4),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2), resource(5, 5, 2));
        final var pair = new Request(4, 5);
        final var request = new Request(3);
        for (int repeated = 4; repeated <= 5; repeated++) {
            final var state = new Engine(model);
            try (final var algorithm = Fgd.cpuRam(state, 2, new Request[]{pair, new Request(repeated)})) {
                state.schedule(algorithm, request);
                assertEquals(repeated - 3, state.placement(3).host());
            }
        }
    }

    @Test
    void cpuRamScoreUsesNominalMemdiskProfilesAndActualPlacementDebit() throws Exception {
        final var disk = DiskPlan.newBuilder().setMedia(Media.MEMDISK).setExtents(4)
                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1);
        final var state = new Engine(model(
                alloc(1, new int[]{1, 2}, 8, 8), alloc(2, new int[]{18}, 8),
                alloc(3, new int[]{1, 2}, 4, 100), alloc(4, new int[]{2}, 100), alloc(5, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.MEMDISK)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD).addDisks(disk)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD)
                        .addDisks(disk.clone().setExtents(2).setMetadataExtents(1))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 4, 4), resource(5, 5, 5)));
        final var planner = new GreedyPlanner(state);
        final var score = planner.cpuRamFragmentationScorer(new Request[]{new Request(3)});
        assertEquals(1.0 / 8, score.applyAsDouble(scalarPlan(5, 1)), 1e-12);
        final var volume = new Assignment(4, 1, 0, new int[]{0, 1}, new int[]{2});
        assertEquals(3.0 / 8, score.applyAsDouble(new Assignment[]{volume}), 1e-12);
        assertEquals(8, state.available(1, 2));
    }

    @Test
    void paperExamplePreservesTheUsefulRemaindersAndRecomputesAfterRemoval() throws Exception {
        final var state = new Engine(paperModel());
        final var a = new Request(3);
        final var b = new Request(4);
        final var c = new Request(5);
        try (final var algorithm = new Fgd(state, 42, new Request[]{a, b, c})) {
            state.schedule(algorithm, new Request(2));
            final int halfGpu = state.placement(2).gpus()[0];
            final int fullGpu = otherGpu(state, halfGpu);
            state.schedule(algorithm, a);
            assertArrayEquals(new int[]{fullGpu}, state.placement(3).gpus());
            state.schedule(algorithm, b, c);
            assertArrayEquals(new int[]{halfGpu}, state.placement(4).gpus());
            assertArrayEquals(new int[]{fullGpu}, state.placement(5).gpus());
            assertEquals(0, state.available(1, 11), 1e-12);
            state.remove(c);
            state.remove(b);
            state.remove(a);
            assertEquals(10, state.gpuAvailable(halfGpu));
            assertEquals(20, state.gpuAvailable(fullGpu));
            state.schedule(algorithm, a);
            assertArrayEquals(new int[]{fullGpu}, state.placement(3).gpus());
        }
    }

    @Test
    void repeatedCatalogueProfilesChangeThePreferredDevice() throws Exception {
        final var state = new Engine(paperModel());
        final var a = new Request(3);
        final var b = new Request(4);
        final var c = new Request(5);
        try (final var algorithm = new Fgd(state, 42, new Request[]{a, b, c, c})) {
            state.schedule(algorithm, new Request(2));
            final int halfGpu = state.placement(2).gpus()[0];
            state.schedule(algorithm, a);
            assertArrayEquals(new int[]{halfGpu}, state.placement(3).gpus());
            assertEquals(4, state.gpuAvailable(halfGpu));
            assertEquals(20, state.gpuAvailable(otherGpu(state, halfGpu)));
        }
    }

    @Test
    void comparesHostDeltasRatherThanAbsoluteFragmentationAfterPlacement() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, 1),
                alloc(3, new int[]{11}, .5), alloc(4, new int[]{11}, .3), alloc(5, new int[]{11}, .7),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setPinnedHost(1)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 3), resource(4, 4, 2), resource(5, 5, 2)));
        try (final var algorithm = new Fgd(state, 42, new Request[]{new Request(5)})) {
            state.schedule(algorithm, new Request(3));
            final int firstGpu = state.placement(3).gpus()[0];
            state.schedule(algorithm, new Request(4));
            assertEquals(1, state.placement(4).host());
            assertArrayEquals(new int[]{firstGpu}, state.placement(4).gpus());
            assertEquals(4, state.gpuAvailable(firstGpu));
            assertEquals(20, state.gpuAvailable(state.model().gpus.first(2, 11)));
        }
    }

    @Test
    void cpuOnlyPlacementAvoidsStrandingGpuCapacityUnlessPinned() throws Exception {
        for (final boolean pinned : new boolean[]{false, true}) {
            final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                    alloc(1, new int[]{1, 11}, 8, 1), alloc(2, new int[]{1}, 8),
                    alloc(3, new int[]{1}, 4), alloc(4, new int[]{1, 11}, 8, 1),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(pinned ? 1 : 0)),
                    rules(Constraints.newBuilder().setId(3).setKind(Kind.POD)),
                    resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 3)));
            final var request = new Request(3);
            try (final var algorithm = new Fgd(state, 42, new Request[]{new Request(4)})) {
                state.schedule(algorithm, request);
                assertEquals(pinned ? 1 : 2, state.placement(3).host());
                assertEquals(4, state.available(state.placement(3).host(), 1));
                assertEquals(20, state.gpuAvailable(state.model().gpus.first(1, 11)));
                state.remove(request);
                assertEquals(8, state.available(1, 1));
                assertEquals(8, state.available(2, 1));
            }
        }
    }

    @Test
    void compoundGpuRequestsKeepStorageColocationAndAtomicFailure() throws Exception {
        final var disk = DiskPlan.newBuilder().setMedia(Media.HDD).setExtents(1)
                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1);
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 11, 17}, 8, 2, 2), alloc(2, new int[]{18}, 10),
                alloc(3, new int[]{1, 11, 17}, 1, .6, .25),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setTarget(4).setDomain(1))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 3, 4), resource(5, 3, 3)));
        final var accepted = new Request(3, 4);
        try (final var algorithm = new Fgd(state, 42, new Request[]{accepted})) {
            state.schedule(algorithm, new Request(3, 4, 5));
            assertNull(state.placement(3));
            assertNull(state.placement(4));
            assertNull(state.placement(5));
            assertEquals(0, state.revision());
            assertEquals(10, state.available(2, 18));
            state.schedule(algorithm, accepted);
            assertNotNull(state.placement(3));
            assertNotNull(state.placement(4));
            assertEquals(1, state.placement(3).host());
            assertEquals(1, state.placement(4).host());
            assertEquals(9, state.available(2, 18));
            assertEquals(.8, state.available(1, 11), 1e-12);
            assertEquals(1.5, state.available(1, 17), 1e-12);
            for (int gpu = state.model().gpus.first(1, 11); gpu < state.model().gpus.end(1, 11); gpu++) {
                assertEquals(8, state.gpuAvailable(gpu));
            }
            for (final int gpu : state.placement(3).gpus()) {
                assertEquals(1, state.model().gpus.host(gpu));
                assertTrue(state.gpuAvailable(gpu) >= 0);
            }
            state.remove(accepted);
            assertEquals(10, state.available(2, 18));
            for (int gpu = 1; gpu <= state.model().gpus.size(); gpu++) {
                assertEquals(20, state.gpuAvailable(gpu));
            }
        }
    }

    @Test
    void emptyCatalogueAndClustersWithoutGpuHaveRepeatableSeededTies() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2), resource(5, 2, 2));
        final int[][] placed = new int[2][3];
        for (int trial = 0; trial < placed.length; trial++) {
            final var state = new Engine(model);
            try (final var algorithm = new Fgd(state, 42, new Request[0])) {
                state.schedule(algorithm, new Request(3), new Request(4), new Request(5));
                for (int index = 0; index < placed[trial].length; index++) {
                    placed[trial][index] = state.placement(index + 3).host();
                    assertEquals(0, state.placement(index + 3).gpus().length);
                }
                assertEquals(6, algorithm.plannerCalls());
            }
        }
        assertArrayEquals(placed[0], placed[1]);
        final var aggregate = new Engine(model(ClusterSpec.GpuMode.AGGREGATE,
                alloc(1, new int[]{11}, 1), rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                resource(1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Fgd(aggregate, 42, new Request[0]));
    }

    private static Assignment[] scalarPlan(int resource, int host) {
        return new Assignment[]{new Assignment(resource, host, 0, new int[]{0}, new int[0])};
    }

    private static int otherGpu(Engine state, int gpu) {
        final int first = state.model().gpus.first(1, 11);
        return gpu == first ? first + 1 : first;
    }

    private static ClusterSpec paperModel() throws Exception {
        return model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .5), alloc(3, new int[]{11}, .3),
                alloc(4, new int[]{11}, .7),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 2, 2), resource(5, 4, 2));
    }
}
