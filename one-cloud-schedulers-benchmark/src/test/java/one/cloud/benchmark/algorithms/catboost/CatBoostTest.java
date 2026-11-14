package one.cloud.benchmark.algorithms.catboost;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;

import one.cloud.benchmark.algorithms.fitcount.CandidateSampler;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.PortfolioFitCount;
import one.cloud.benchmark.algorithms.fitcount.RandomNFitCount;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostTest {
    @Test
    void sharedSamplerPreservesBaselineStreamAndSamplesOnlyItsComplement() {
        final var sampler = new CandidateSampler(44);
        final var reference = new SplittableRandom(44 ^ 0x6A09E667F3BCC909L);
        final var auxiliary = new SplittableRandom(991);
        for (final int count : new int[]{1, 20, 21, 28, 65, 21, 28, 65}) {
            final int[] pool = new int[count];
            Arrays.setAll(pool, index -> index);
            final int[] expected = new int[Math.min(count, 20)];
            int remaining = count;
            if (count <= 20) {
                System.arraycopy(pool, 0, expected, 0, count);
            } else {
                for (int index = 0; index < expected.length; index++) {
                    final int chosen = reference.nextInt(remaining);
                    expected[index] = pool[chosen];
                    pool[chosen] = pool[--remaining];
                }
                Arrays.sort(expected);
            }
            final var selected = sampler.sample(count, 20);
            assertArrayEquals(expected, selected);
            final var complement = sampler.sampleRemaining(8, auxiliary);
            assertEquals(Math.min(8, count - selected.length), complement.length);
            for (int index = 0; index < complement.length; index++) {
                assertTrue(Arrays.binarySearch(selected, complement[index]) < 0);
                assertTrue(index == 0 || complement[index - 1] < complement[index]);
            }
        }
    }

    @Test
    void randomOnlyPortfolioMatchesRandomNThroughChangingCandidateCountsAndDeletes() throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 8));
        records.add(alloc(2, new int[]{1}, 1));
        records.add(alloc(3, new int[]{1}, 100));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setCandidates(2)));
        records.add(rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setCandidates(3)));
        records.add(rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)));
        records.add(rules(Constraints.newBuilder().setId(5).setKind(Kind.POD).setPinnedHost(1)));
        records.add(one.cloud.benchmark.testsupport.TestModel.candidates(2, java.util.stream.IntStream.rangeClosed(1, 21).toArray()));
        records.add(one.cloud.benchmark.testsupport.TestModel.candidates(3, java.util.stream.IntStream.rangeClosed(1, 28).toArray()));
        for (int host = 1; host <= 65; host++) {
            records.add(resource(host, 1, 1));
        }
        for (int index = 0; index < 4; index++) {
            records.add(resource(66 + index, 2, 2 + index));
        }
        records.add(resource(70, 3, 4));
        final var model = model(records.toArray(Record[]::new));
        final var requests = new Request[]{new Request(66), new Request(67), new Request(68),
                new Request(69), new Request(70)};
        final var baseline = new Engine(model);
        final var state = new Engine(model);
        try (final var random = new RandomNFitCount(baseline, 44, requests, 20, 10, 1);
                final var portfolio = new PortfolioFitCount(state, 44, requests, 20, 0, 0, 10, 1)) {
            for (int pass = 0; pass < 3; pass++) {
                for (final var request : requests) {
                    baseline.schedule(random, request);
                    state.schedule(portfolio, request);
                    final var expected = baseline.placement(request.resource(0));
                    final var actual = state.placement(request.resource(0));
                    if (expected == null) {
                        assertNull(actual);
                    } else {
                        assertEquals(expected.host(), actual.host());
                        assertEquals(expected.numa(), actual.numa());
                    }
                }
                for (final var request : requests) {
                    if (baseline.placement(request.resource(0)) != null) {
                        baseline.remove(request);
                        state.remove(request);
                    }
                }
            }
            assertEquals(random.plannerCalls(), portfolio.plannerCalls());
            assertEquals(0, portfolio.counters().get("fragmentation_scores"));
            assertEquals(0, portfolio.counters().get("least_scores"));
        }
    }

    @Test
    void diskExtentFeaturesUseFixedTrainingUnit(@TempDir Path directory) throws Exception {
        final float[][] actual = new float[2][];
        int index = 0;
        for (long extentBytes : new long[]{1L << 20, 64L << 20}) {
            final var source = directory.resolve("source-" + extentBytes);
            writeTrace(source, new Record[]{
                    alloc(1, new int[]{1, 2}, 10, 100),
                    alloc(2, new int[]{18, 19}, (64L << 20) / extentBytes, 1),
                    alloc(3, new int[]{1, 2}, 1, 1),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.SSD)),
                    rules(Constraints.newBuilder().setId(3).setKind(Kind.POD)
                            .addDisks(DiskPlan.newBuilder().setMedia(Media.SSD).setExtents(1)
                                    .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1))),
                    resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3)
            });
            final var path = directory.resolve("model-" + extentBytes + ".pb");
            try (final var input = Files.newInputStream(source.resolve("snapshot.pb"));
                    final var output = Files.newOutputStream(path)) {
                final var first = Record.parseDelimitedFrom(input);
                Record.newBuilder().setHeader(first.getHeader().toBuilder().setExtentBytes(extentBytes))
                        .build().writeDelimitedTo(output);
                for (var record = Record.parseDelimitedFrom(input); record != null;
                        record = Record.parseDelimitedFrom(input)) {
                    record.writeDelimitedTo(output);
                }
            }
            final var state = new Engine(ClusterSpec.from(Dataset.load(path)));
            final var encoder = new CandidateFeatures(state);
            final int slot = index++;
            state.schedule(new SchedulingAlgorithm(state) {
                @Override
                public void schedule(Request request) {
                    final var plans = candidates(state, new GreedyPlanner(state), request);
                    actual[slot] = encoder.prepare(request, plans, plans.length).extract(0, 1)[0];
                }
            }, new Request(3));
        }
        assertEquals(1, actual[0][CandidateFeatures.names().indexOf("device_extents_min")]);
        assertArrayEquals(actual[0], actual[1]);
        for (String name : new String[]{"fitcount", "rollout"}) {
            try (final var scorer = new CatBoostScorer(null,
                    "/one/cloud/benchmark/algorithms/catboost/" + name + ".cbm")) {
                final var scores = scorer.score(actual);
                assertEquals(scores[0], scores[1]);
            }
        }
    }

    @Test
    void singletonFeaturesAndNativePredictionsMatchArchivedEncoder() throws Exception {
        for (final String fixture : new String[]{"cpu", "intel"}) {
            final int dimension = fixture.equals("cpu") ? 1 : 17;
            final var state = new Engine(model(
                    alloc(1, new int[]{Math.min(dimension, 2), Math.max(dimension, 2)},
                            dimension == 1 ? 8 : 2048, dimension == 1 ? 2048 : 8),
                    alloc(2, new int[]{Math.min(dimension, 2), Math.max(dimension, 2)},
                            dimension == 1 ? 4 : 4096, dimension == 1 ? 4096 : 4),
                    alloc(3, new int[]{Math.min(dimension, 2), Math.max(dimension, 2)},
                            dimension == 1 ? 2 : 2048, dimension == 1 ? 2048 : 2), alloc(4, new int[]{1, 2}, 8, 2048),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(1)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                    resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2)));
            final var encoder = new CandidateFeatures(state);
            final var planner = new GreedyPlanner(state);
            final String prefix = "/one/cloud/benchmark/algorithms/catboost/";
            try (final var reader = new BufferedReader(new InputStreamReader(
                    getClass().getResourceAsStream(prefix + "legacy-" + fixture + ".csv"), StandardCharsets.UTF_8));
                    final var fitcount = new CatBoostScorer(null, prefix + "fitcount.cbm");
                    final var rollout = new CatBoostScorer(null, prefix + "rollout.cbm")) {
                final var lines = reader.lines().toList();
                assertEquals(CandidateFeatures.names(), Arrays.asList(lines.getFirst().split(",")).subList(5, 116));
                final int[] row = {1};
                final var probe = new SchedulingAlgorithm(state) {
                    @Override
                    public void schedule(Request request) throws Exception {
                        final var plans = candidates(state, planner, request);
                        final var raw = encoder.prepare(request, plans, plans.length).extract(0, plans.length * request.size());
                        final var fitcountScores = fitcount.score(raw);
                        final var rolloutScores = rollout.score(raw);
                        for (int candidate = 0; candidate < raw.length; candidate++, row[0]++) {
                            final var expected = lines.get(row[0]).split(",");
                            final float[] values = new float[111];
                            for (int column = 0; column < values.length; column++) {
                                values[column] = Float.parseFloat(expected[column + 5]);
                            }
                            assertArrayEquals(values, raw[candidate]);
                            assertEquals(Double.parseDouble(expected[3]), fitcountScores[candidate], 1e-12);
                            assertEquals(Double.parseDouble(expected[4]), rolloutScores[candidate], 1e-12);
                        }
                        if (request.resource(0) == 3) {
                            state.place(request, plans[plans.length - 1]);
                        }
                    }
                };
                state.schedule(probe, new Request(3));
                state.schedule(probe, new Request(4));
                assertEquals(lines.size(), row[0]);
            }
        }
    }

    @Test
    void jointTop1UsesSumOfActualMemberScores() throws Exception {
        final var state = new Engine(cpuModel());
        final var request = new Request(3, 4);
        final float[][][] extracted = new float[1][][];
        final var encoder = new CandidateFeatures(state);
        state.schedule(new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                final var plans = candidates(state, new GreedyPlanner(state), request);
                extracted[0] = encoder.prepare(request, plans, plans.length).extract(0, plans.length * request.size());
            }
        }, request);
        final var raw = extracted[0];
        assertEquals(4, raw.length);
        assertEquals(111, raw[0].length);
        assertEquals(2, raw[0][CandidateFeatures.names().indexOf("candidate_count")]);
        assertEquals(1, raw[0][CandidateFeatures.names().indexOf("request_vcores")]);
        assertEquals(2, raw[1][CandidateFeatures.names().indexOf("request_vcores")]);
        final var actual = new Engine(state.model());
        try (final var scorer = new CatBoostScorer(null,
                "/one/cloud/benchmark/algorithms/catboost/fitcount.cbm");
                final var algorithm = new CatBoostTop1(actual, 42, new Request[]{request})) {
            final var scores = scorer.score(raw);
            final int expected = scores[0] + scores[1] >= scores[2] + scores[3] ? 1 : 2;
            actual.schedule(algorithm, request);
            assertEquals(expected, actual.placement(3).host());
            assertEquals(expected, actual.placement(4).host());
            assertEquals(64, algorithm.modelSha256().length());
        }
    }

    @Test
    void jointInferenceCrossesBatchBoundaryWithoutChangingScores() throws Exception {
        final int members = 600;
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1, 2}, 2000, 1024));
        records.add(alloc(2, new int[]{1, 2}, 1000, 2048));
        records.add(alloc(3, new int[]{1, 2}, 1, 1));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        records.add(resource(1, 1, 1));
        records.add(resource(2, 2, 1));
        final int[] ids = new int[members];
        for (int member = 0; member < members; member++) {
            ids[member] = member + 3;
            records.add(resource(ids[member], 3, 2));
        }
        final var model = model(records.toArray(Record[]::new));
        final var probeState = new Engine(model);
        final var encoder = new CandidateFeatures(probeState);
        final int[] expectedHost = new int[1];
        final var request = new Request(ids);
        try (final var scorer = new CatBoostScorer(null,
                "/one/cloud/benchmark/algorithms/catboost/fitcount.cbm")) {
            probeState.schedule(new SchedulingAlgorithm(probeState) {
                @Override
                public void schedule(Request request) throws Exception {
                    final var plans = candidates(probeState, new GreedyPlanner(probeState), request);
                    final var decision = encoder.prepare(request, plans, plans.length);
                    assertEquals(1200, decision.rows());
                    assertThrows(IllegalArgumentException.class,
                            () -> decision.extract(0, CandidateFeatures.Decision.MAX_BATCH_ROWS + 1));
                    final double[] sums = new double[2];
                    for (int candidate = 0; candidate < 2; candidate++) {
                        for (double score : scorer.score(decision.extract(candidate * members, members))) {
                            sums[candidate] += score;
                        }
                    }
                    expectedHost[0] = sums[0] >= sums[1] ? 1 : 2;
                }
            }, request);
        }
        final var state = new Engine(model);
        try (final var algorithm = new CatBoostTop1(state, 42, new Request[]{request})) {
            state.schedule(algorithm, request);
            for (int id : ids) {
                assertEquals(expectedHost[0], state.placement(id).host());
            }
        }
    }

    @Test
    void fullShortlistPreservesFitCountAcrossRemoval() throws Exception {
        final var model = cpuModel();
        final var request = new Request(3, 4);
        for (int variant : new int[]{1, 3}) {
            final var baseline = new Engine(model);
            try (final var fitcount = new FitCount(baseline, 42, new Request[]{request})) {
                final var state = new Engine(model);
                try (final var algorithm = algorithm(variant, state, request)) {
                    for (int repeat = 0; repeat < 2; repeat++) {
                        baseline.schedule(fitcount, request);
                        state.schedule(algorithm, request);
                        assertEquals(baseline.placement(3).host(), state.placement(3).host());
                        baseline.remove(request);
                        state.remove(request);
                    }
                }
            }
        }
    }

    @Test
    void allVariantsRespectAtomicGpuDeviceCapacityAndRemoval() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11, 17}, 1, 1),
                alloc(2, new int[]{11, 17}, .6, .4), alloc(3, new int[]{11, 17}, .4, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 2), resource(5, 2, 2));
        for (int variant = 0; variant < 4; variant++) {
            final var state = new Engine(model);
            final var impossible = new Request(3, 5);
            final var request = new Request(3, 4);
            try (final var algorithm = algorithm(variant, state, request)) {
                state.schedule(algorithm, impossible);
                assertNull(state.placement(3));
                assertNull(state.placement(5));
                for (int repeat = 0; repeat < 2; repeat++) {
                    state.schedule(algorithm, request);
                    assertNotNull(state.placement(3));
                    assertNotNull(state.placement(4));
                    assertEquals(state.placement(3).host(), state.placement(4).host());
                    assertEquals(2, state.placement(3).gpus().length);
                    final int host = state.placement(3).host();
                    assertEquals(0, state.available(host, 11), 1e-12);
                    assertEquals(0, state.available(host, 17), 1e-12);
                    state.remove(request);
                    assertEquals(1, state.available(host, 11), 1e-12);
                }
            }
        }
    }

    @Test
    void invalidShortlistFailsBeforeLoadingModel() throws Exception {
        final var state = new Engine(cpuModel());
        assertThrows(IllegalArgumentException.class,
                () -> new CatBoostTopNFitCount(state, 42, new Request[0], 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CatBoostRolloutTopNFitCount(state, 42, new Request[0], -1));
    }

    private static Assignment[][] candidates(Engine state, GreedyPlanner planner, Request request) {
        final var plans = new ArrayList<Assignment[]>();
        for (int host = 1; host <= state.model().hostCount; host++) {
            final var plan = planner.plan(request, host, 0);
            if (plan != null) {
                plans.add(plan);
            }
        }
        return plans.toArray(Assignment[][]::new);
    }

    private static CatBoostAlgorithm algorithm(int variant, Engine state, Request request) throws Exception {
        final var catalogue = new Request[]{request};
        return switch (variant) {
            case 0 -> new CatBoostTop1(state, 42, catalogue);
            case 1 -> new CatBoostTopNFitCount(state, 42, catalogue, 20);
            case 2 -> new CatBoostRolloutTop1(state, 42, catalogue);
            case 3 -> new CatBoostRolloutTopNFitCount(state, 42, catalogue, 20);
            default -> throw new IllegalArgumentException("Unknown test variant");
        };
    }

    private static ClusterSpec cpuModel() throws Exception {
        return model(alloc(1, new int[]{1, 2}, 8, 5), alloc(2, new int[]{1, 2}, 5, 8),
                alloc(3, new int[]{1, 2}, 1, 1), alloc(4, new int[]{1, 2}, 2, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2));
    }
}
