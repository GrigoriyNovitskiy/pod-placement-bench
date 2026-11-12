package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Domain;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
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

class BulkFitCountTest {
    @Test
    void countDpUsesWholeNonconvexOptionsAndMatchesExhaustiveLexicographicSearch() throws Exception {
        final var model = scalar(3, 3, null);
        final var state = new Engine(model);
        final var requests = requests(4, 3);
        final var work = new CountDp.Work(100_000_000, 64L << 20);
        final var tree = new BulkModel(state, requests, new int[]{0, 3}, work);
        tree.tree(work);
        final long[][][] trap = {{{0}, {10}, {10}}, {{0}, {6}, {12}}, {{0}}};
        assertArrayEquals(new int[]{0, 2, 0, 0}, new CountDp(tree, 2, 1, options(trap), work).solve());
        final var random = new SplittableRandom(42);
        for (int attempt = 0; attempt < 80; attempt++) {
            final long[][][] curves = new long[3][][];
            for (int host = 0; host < curves.length; host++) {
                curves[host] = new long[random.nextInt(1, 5)][2];
                for (int n = 1; n < curves[host].length; n++) {
                    curves[host][n][0] = random.nextInt(-5, 6);
                    curves[host][n][1] = random.nextInt(-5, 6);
                }
            }
            final var budget = new CountDp.Work(100_000_000, 64L << 20);
            final int[] result = new CountDp(tree, 3, 2, options(curves), budget).solve();
            final long[] actual = {Arrays.stream(result).sum(), 0, 0};
            for (int host = 1; host < result.length; host++) {
                for (int component = 0; component < 2; component++) {
                    actual[component + 1] += curves[host - 1][result[host]][component];
                }
            }
            long[] best = {-1, 0, 0};
            for (int a = 0; a < curves[0].length; a++) {
                for (int b = 0; b < curves[1].length; b++) {
                    for (int c = 0; c < curves[2].length; c++) {
                        if (a + b + c <= 3) {
                            final long[] candidate = {a + b + c,
                                    curves[0][a][0] + curves[1][b][0] + curves[2][c][0],
                                    curves[0][a][1] + curves[1][b][1] + curves[2][c][1]};
                            if (candidate[0] > best[0] || candidate[0] == best[0]
                                    && (candidate[1] < best[1] || candidate[1] == best[1] && candidate[2] < best[2])) {
                                best = candidate;
                            }
                        }
                    }
                }
            }
            assertArrayEquals(best, actual);
        }
    }

    @Test
    void laminarCapsLimitJointCountsAndCrossingOpaqueDomainsSelectTheIncumbent() throws Exception {
        final var laminar = scalar(3, 3, Limit.newBuilder().setDomain(3).setMax(1).setHard(true));
        final var state = new Engine(laminar);
        final var requests = requests(4, 3);
        final var work = new CountDp.Work(100_000_000, 64L << 20);
        final var tree = new BulkModel(state, requests, new int[]{0, 3}, work);
        tree.tree(work);
        final long[][][] costs = {{{0}, {0}, {0}}, {{0}, {1}, {2}}, {{0}, {5}, {10}}};
        assertArrayEquals(new int[]{0, 1, 0, 1}, new CountDp(tree, 3, 1, options(costs), work).solve());
        final var crossing = model(
                alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(1))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(2))),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(1))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(1))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(2))
                        .addDomains(Domain.newBuilder().setLevel(7).setValue(1))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(3).setMax(1).setHard(true))
                        .addLimits(Limit.newBuilder().setDomain(7).setMax(1).setHard(true))),
                resource(1, 1, 1), resource(2, 1, 2), resource(3, 1, 3),
                resource(4, 2, 4), resource(5, 2, 4));
        final var crossed = new Engine(crossing);
        try (final var algorithm = BulkFitCount.dynamicProgramming(crossed, 42,
                new Request[]{new Request(4)}, 128, 256)) {
            crossed.schedule(algorithm, new Request(4), new Request(5));
            assertEquals(1, algorithm.counters().get("fallback_crossing_domains"));
            assertEquals(0, algorithm.counters().get("certified_bulks"));
            assertTrue(crossed.revision() > 0);
        }
    }

    @Test
    void sharedGroupMultiplicityIsConvertedToReplicasWithoutInventingOnePerHost() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(1))),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(3).setMax(3).setHard(true))
                        .addLinks(Link.newBuilder().setTarget(4).setDomain(1))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD).setGroup(1)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(3).setMax(3).setHard(true))
                        .addLinks(Link.newBuilder().setTarget(6).setDomain(1))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3),
                resource(5, 2, 4), resource(6, 2, 3));
        final var state = new Engine(model);
        final var requests = new Request[]{new Request(3, 4), new Request(5, 6)};
        final var work = new CountDp.Work(100_000_000, 64L << 20);
        final var tree = new BulkModel(state, requests, new int[]{0, 4}, work);
        tree.tree(work);
        assertEquals(1, tree.nodes[tree.root].cap);
        try (final var algorithm = BulkFitCount.dynamicProgramming(state, 42, requests, 128, 256)) {
            state.schedule(algorithm, requests);
            assertNotNull(state.placement(3));
            assertNull(state.placement(5));
            assertEquals(2, state.groupCount(1));
            assertEquals(1, state.revision());
        }
    }

    @Test
    void numaLimitsRemainLocalAndBothAlgorithmsRestoreCapacityAfterRemoval() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, 2), alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(2).setMax(1).setHard(true))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 3, 4));
        for (final boolean dp : new boolean[]{false, true}) {
            final var state = new Engine(model);
            final var requests = new Request[]{new Request(4), new Request(5)};
            try (final var algorithm = algorithm(state, requests, dp)) {
                state.schedule(algorithm, requests);
                assertNotNull(state.placement(4));
                assertNotNull(state.placement(5));
                assertTrue(state.placement(4).numa() != state.placement(5).numa());
                assertEquals(1, algorithm.counters().get("certified_bulks"));
                assertEquals(2, state.revision());
                state.remove(requests[1]);
                state.remove(requests[0]);
                assertEquals(4, state.available(1, 1));
                state.schedule(algorithm, requests);
                assertNotNull(state.placement(4));
                assertNotNull(state.placement(5));
                assertEquals(6, state.revision());
            }
        }
    }

    @Test
    void privateShardGroupsAndReboundRaidGeometryPreservePhysicalAccounting() throws Exception {
        final var raid = DiskPlan.newBuilder().setMedia(Media.SSD).setLayout(Layout.RAID1)
                .setExtents(2).setMetadataExtents(1).setIops(4).setSpindles(2)
                .setMinDevices(2).setMaxDevices(2).setMinStripes(1).setMaxStripes(2);
        final var alternative = DiskPlan.newBuilder().setMedia(Media.SSD).setLayout(Layout.LINEAR)
                .setExtents(1).setSpindles(3).setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1);
        final var model = model(
                alloc(1, new int[]{1, 7, 9}, 8, 100, 100), alloc(2, new int[]{18, 19, 20}, 10, 4, 20),
                alloc(3, new int[]{18, 19, 20}, 10, 1, 20), alloc(4, new int[]{1}, 1), alloc(5, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.SSD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)
                        .setAlternativeMedia(Media.SSD).setAlternativeSpindles(8)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLinks(Link.newBuilder().setTarget(6).setDomain(1))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).setGroup(2).addDisks(raid).addDisks(alternative)),
                rules(Constraints.newBuilder().setId(6).setKind(Kind.POD).setGroup(1)
                        .addLinks(Link.newBuilder().setTarget(8).setDomain(1))),
                rules(Constraints.newBuilder().setId(7).setKind(Kind.SHARD).setGroup(3).addDisks(raid).addDisks(alternative)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 3, 3),
                resource(5, 4, 4), resource(6, 5, 5), resource(7, 4, 6), resource(8, 5, 7));
        final var requests = new Request[]{new Request(5, 6), new Request(7, 8)};
        for (final boolean dp : new boolean[]{false, true}) {
            final var state = new Engine(model);
            try (final var algorithm = algorithm(state, requests, dp)) {
                state.schedule(algorithm, requests);
                assertEquals(1, algorithm.counters().get("certified_bulks"));
                assertEquals(2, state.revision());
                assertEquals(6, state.available(1, 1));
                assertEquals(4, state.available(2, 18));
                assertEquals(4, state.available(3, 18));
                assertEquals(12, state.available(2, 20));
                assertEquals(0, state.available(4, 19));
                assertEquals(2, state.available(4, 19, Media.SSD_VALUE));
                assertArrayEquals(new int[]{2, 3, 4}, state.placement(6).devices());
                assertArrayEquals(new int[]{2, 3, 4}, state.placement(8).devices());
                state.remove(requests[1]);
                state.remove(requests[0]);
                assertEquals(10, state.available(2, 18));
                assertEquals(1, state.available(4, 19));
                assertEquals(8, state.available(4, 19, Media.SSD_VALUE));
                assertEquals(20, state.available(2, 20));
                state.schedule(algorithm, requests[0]);
                assertEquals(1, algorithm.counters().get("single_delegations"));
                assertNotNull(state.placement(6));
            }
        }
    }

    @Test
    void countDpPublishesCompleteBulkAndUpdatesTheRealFallback() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2}, 12, 8), alloc(2, new int[]{1, 2}, 12, 12),
                alloc(3, new int[]{1, 2}, 8, 5), alloc(4, new int[]{1, 2}, 1, 3),
                alloc(5, new int[]{1, 2}, 2, 4), alloc(6, new int[]{1, 2}, 3, 7),
                alloc(7, new int[]{1, 2}, 5, 10), alloc(8, new int[]{1, 2}, 8, 11),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 1),
                resource(4, 4, 2), resource(5, 4, 2), resource(6, 4, 2), resource(7, 4, 2),
                resource(8, 5, 3), resource(9, 6, 3), resource(10, 7, 3), resource(11, 8, 3));
        final var pending = requests(4, 4);
        final var catalogue = requests(8, 4);
        final var sequential = new Engine(model);
        try (final var fit = new FitCount(sequential, 42, catalogue, 128, 256)) {
            sequential.schedule(fit, pending);
            final var probe = new GreedyPlanner(sequential);
            assertEquals(2, probe.fitCount(catalogue[0], 1, 128) + probe.fitCount(catalogue[0], 2, 128)
                    + probe.fitCount(catalogue[0], 3, 128));
        }
        final var state = new Engine(model);
        try (final var algorithm = BulkFitCount.dynamicProgramming(state, 42, catalogue, 128, 256)) {
            state.schedule(algorithm, pending);
            assertEquals(1, algorithm.counters().get("optimized_bulks"));
            assertEquals(4, state.revision());
            for (final var request : pending) {
                assertEquals(2, state.placement(request.resource(0)).host());
            }
            final var probe = new GreedyPlanner(state);
            assertEquals(3, probe.fitCount(catalogue[0], 1, 128) + probe.fitCount(catalogue[0], 2, 128)
                    + probe.fitCount(catalogue[0], 3, 128));
            for (int index = pending.length - 1; index >= 0; index--) {
                state.remove(pending[index]);
            }
            assertEquals(12, state.available(2, 1));
            assertEquals(12, state.available(2, 2));
            state.schedule(algorithm, pending[0]);
            assertNotNull(state.placement(4));
            assertEquals(1, algorithm.counters().get("single_delegations"));
        }
    }

    @Test
    void singleRequestDelegationPreservesFitcountSeedAndEvictionChangesNoDecision() throws Exception {
        final var model = scalar(3, 3, null);
        final var pending = requests(4, 3);
        int[] expected = null;
        for (int mode = 0; mode < 3; mode++) {
            final var state = new Engine(model);
            final var actual = new int[3];
            if (mode == 0) {
                try (final var fit = new FitCount(state, 42, pending, 128, 1)) {
                    for (int index = 0; index < pending.length; index++) {
                        state.schedule(fit, pending[index]);
                        actual[index] = state.placement(pending[index].resource(0)).host();
                    }
                }
            } else {
                try (final var fit = mode == 1 ? new BulkFitCount(state, 42, pending, 128, 1)
                        : BulkFitCount.dynamicProgramming(state, 42, pending, 128, 256)) {
                    for (int index = 0; index < pending.length; index++) {
                        state.schedule(fit, pending[index]);
                        actual[index] = state.placement(pending[index].resource(0)).host();
                    }
                }
            }
            if (expected == null) {
                expected = actual;
            } else {
                assertArrayEquals(expected, actual);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> BulkFitCount.dynamicProgramming(new Engine(model),
                42, pending, 128, 256, 0, 1));
    }

    @Test
    void bulksReuseHostScoresAndKeepCacheBoundsOutOfPlacementDecisions() throws Exception {
        final var model = scalar(16, 32, null);
        final var pending = requests(17, 32);
        final var catalogue = new Request[]{pending[0], new Request(17, 18)};
        final var sequential = new Engine(model);
        final long sequentialCalls;
        try (final var fit = new FitCount(sequential, 42, catalogue, 128, 256)) {
            sequential.schedule(fit, pending);
            sequentialCalls = fit.plannerCalls();
        }
        int[] expected = null;
        for (final int rows : new int[]{1, 256}) {
            final var state = new Engine(model);
            final var actual = new int[2 * pending.length];
            try (final var fit = new BulkFitCount(state, 42, catalogue, 128, rows)) {
                for (int cycle = 0; cycle < 2; cycle++) {
                    state.schedule(fit, pending);
                    for (int index = 0; index < pending.length; index++) {
                        actual[cycle * pending.length + index] = state.placement(pending[index].resource(0)).host();
                    }
                    for (int index = 0; index < pending.length; index += 2) {
                        assertEquals(actual[cycle * pending.length + index], actual[cycle * pending.length + index + 1]);
                    }
                    for (int index = pending.length - 1; index >= 0; index--) {
                        state.remove(pending[index]);
                    }
                    assertEquals(16 * 2.5, totalCpu(state));
                }
                assertEquals(2, fit.counters().get("optimized_bulks"));
                assertTrue(fit.counters().get("marginal_evaluations") > 0);
                assertTrue(fit.counters().get("marginal_cache_hits") > 0);
                assertTrue(fit.plannerCalls() < sequentialCalls);
            }
            if (expected == null) {
                expected = actual;
            } else {
                assertArrayEquals(expected, actual);
            }
        }
    }

    @Test
    void bulksRespectChangingDomainLimitsAndFallBackWithoutTrialPlacements() throws Exception {
        final var model = scalar(8, 12, Limit.newBuilder().setDomain(3).setMax(2).setHard(true));
        final var pending = requests(9, 12);
        for (final boolean dp : new boolean[]{false, true}) {
            final var state = new Engine(model);
            try (final var fit = dp ? BulkFitCount.dynamicProgramming(state, 42, pending, 128, 256)
                    : new BulkFitCount(state, 42, pending, 128, 256)) {
                state.schedule(fit, pending);
                assertEquals(4, state.revision());
                assertEquals(4, state.groupCount(1));
                assertEquals(16, totalCpu(state));
                assertEquals(1, fit.counters().get("optimized_bulks"));
            }
        }
        final var mismatch = model(
                alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(1).setPinnedHost(2)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3));
        final var state = new Engine(mismatch);
        try (final var fit = new BulkFitCount(state, 42, new Request[]{new Request(3)}, 128, 256)) {
            state.schedule(fit, new Request(3), new Request(4));
            assertEquals(1, state.placement(3).host());
            assertEquals(2, state.placement(4).host());
            assertEquals(2, state.revision());
            assertEquals(1, fit.counters().get("fallback_replica_shape"));
            assertEquals(0, fit.counters().get("optimized_bulks"));
        }
        final var limitedModel = scalar(2, 2, null);
        final var limitedRequests = requests(3, 2);
        final var sequential = new Engine(limitedModel);
        try (final var fit = new FitCount(sequential, 42, limitedRequests, 128, 1)) {
            sequential.schedule(fit, limitedRequests);
        }
        for (final var budget : new long[][]{{1, 64L << 20}, {64, 64L << 20}, {100_000_000, 1}}) {
            final var limited = new Engine(limitedModel);
            try (final var fit = BulkFitCount.dynamicProgramming(limited, 42, limitedRequests, 128, 1,
                    budget[0], budget[1])) {
                limited.schedule(fit, limitedRequests);
                assertEquals(2, limited.revision());
                assertEquals(3, totalCpu(limited));
                assertEquals(1, fit.counters().get(budget[1] == 1 ? "fallback_workspace_budget" : "fallback_work_budget"));
                assertEquals(0, fit.counters().get("optimized_bulks"));
                for (final var request : limitedRequests) {
                    final int resource = request.resource(0);
                    assertEquals(sequential.placement(resource).host(), limited.placement(resource).host());
                }
            }
        }
    }

    private static double totalCpu(Engine state) {
        double total = 0;
        for (int host = 1; host <= state.model().hostCount; host++) {
            total += state.available(host, 1);
        }
        return total;
    }

    private static BulkFitCount algorithm(Engine state, Request[] catalogue, boolean dp) {
        return dp ? BulkFitCount.dynamicProgramming(state, 42, catalogue, 128, 256)
                : new BulkFitCount(state, 42, catalogue, 128, 256);
    }

    private static CountDp.Options options(long[][][] costs) {
        return new CountDp.Options() {
            private int host;

            @Override
            public int load(int host) {
                this.host = host - 1;
                return costs[this.host].length - 1;
            }

            @Override
            public long cost(int count, int component) {
                return costs[host][count][component];
            }
        };
    }

    private static Request[] requests(int first, int count) {
        final var result = new Request[count];
        for (int index = 0; index < count; index++) {
            result[index] = new Request(first + index);
        }
        return result;
    }

    private static ClusterSpec scalar(int hosts, int replicas, Limit.Builder limit) throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 2.5));
        records.add(alloc(2, new int[]{1}, 1));
        for (int host = 1; host <= hosts; host++) {
            records.add(rules(Constraints.newBuilder().setId(host).setKind(Kind.HOST)
                    .addDomains(Domain.newBuilder().setLevel(3).setValue(host < 3 ? 1 : 2))));
            records.add(resource(host, 1, host));
        }
        final var pod = Constraints.newBuilder().setId(hosts + 1).setKind(Kind.POD).setGroup(1);
        if (limit != null) {
            pod.addLimits(limit);
        }
        records.add(rules(pod));
        for (int replica = 0; replica < replicas; replica++) {
            records.add(resource(hosts + replica + 1, 2, hosts + 1));
        }
        return model(records.toArray(Record[]::new));
    }
}
