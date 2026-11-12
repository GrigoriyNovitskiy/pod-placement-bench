package one.cloud.benchmark.algorithms.rl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.MaskedFitCount;
import one.cloud.benchmark.algorithms.fitcount.RandomNFitCount;
import one.cloud.benchmark.algorithms.fitcount.CandidateSampler;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.simulator.ManualScenario;
import one.cloud.benchmark.simulator.SimulatorSession;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.candidates;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyNetworkTest {
    @Test
    void shortlistMatchesTop1OrFullFitCountAtItsBounds(@TempDir Path directory) throws Exception {
        final var cluster = model(alloc(1, new int[]{1, 2}, 8, 8192),
                alloc(2, new int[]{1, 2}, 3, 2048),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 1, 1),
                resource(4, 2, 2), resource(5, 2, 2), resource(6, 2, 2));
        final var requests = new Request[]{new Request(4), new Request(5), new Request(6)};
        final var path = write(directory, artifact());
        for (int topN : new int[]{1, 20}) {
            final var expected = new Engine(cluster);
            final var actual = new Engine(cluster);
            try (final var reference = topN == 1 ? new RlTop1(expected, path)
                    : new FitCount(expected, 42, requests, 10, 64);
                    final var shortlist = new RlTopNFitCount(actual, 42, requests, topN, 10, 64, path)) {
                for (final var request : requests) {
                    expected.schedule(reference, request);
                    actual.schedule(shortlist, request);
                    assertEquals(expected.placement(request.resource(0)).host(),
                            actual.placement(request.resource(0)).host());
                }
                expected.remove(requests[0]);
                actual.remove(requests[0]);
                expected.schedule(reference, requests[0]);
                actual.schedule(shortlist, requests[0]);
                assertEquals(expected.placement(4).host(), actual.placement(4).host());
            }
        }
    }

    @Test
    void registeredPolicyReplaysTheSimulatorWithAnExplicitModel(@TempDir Path directory) throws Exception {
        final var input = ManualScenario.compile(ManualScenario.example());
        final var defaults = AlgorithmRegistry.defaults("rl-top1");
        try (final var algorithm = AlgorithmRegistry.create(defaults, new Engine(input.model()), input.catalogue())) {
            assertEquals(PolicyNetwork.load(null).sha256(), algorithm.parameters().get("model_sha256"));
        }
        final var settings = new AlgorithmRegistry.Settings("rl-top1",
                Map.of("model", new JsonPrimitive(write(directory, artifact()).toString())));
        try (final var session = new SimulatorSession(input, List.of(settings))) {
            session.step(input.events().length);
            assertEquals(input.events().length, session.cursor());
            assertEquals(false, session.state(0, 48, 0, 0).get("failed").getAsBoolean());
        }
    }

    @Test
    void selectsFirstNumericalTieWithTolerancePerMember(@TempDir Path directory) throws Exception {
        final var artifact = artifact();
        artifact.getAsJsonArray("hiddenWeights").get(0).getAsJsonArray().set(0, new JsonPrimitive(1));
        artifact.getAsJsonArray("outputWeights").set(0, new JsonPrimitive(1));
        final var network = PolicyNetwork.load(write(directory, artifact));
        final var rows = new float[4][RlFeatures.names().size()];
        rows[2][0] = 7.5e-6f;
        rows[3][0] = 7.5e-6f;
        assertEquals(0, network.select(rows, 2));
        rows[2][0] = .1f;
        rows[3][0] = .1f;
        assertEquals(1, network.select(rows, 2));
        final var chain = new float[3][RlFeatures.names().size()];
        chain[1][0] = 9e-6f;
        chain[2][0] = 18e-6f;
        assertEquals(1, network.select(chain, 1));
        assertArrayEquals(new int[]{1, 2}, network.shortlist(chain, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> network.select(new float[0][], 1));
    }

    @Test
    void exportedFormulaNormalizesThenSumsMembers(@TempDir Path directory) throws Exception {
        final var artifact = artifact();
        artifact.getAsJsonArray("mean").set(0, new JsonPrimitive(.5));
        artifact.getAsJsonArray("scale").set(0, new JsonPrimitive(2));
        artifact.getAsJsonArray("hiddenWeights").get(0).getAsJsonArray()
                .set(0, new JsonPrimitive(3));
        artifact.getAsJsonArray("hiddenBias").set(0, new JsonPrimitive(.2));
        artifact.getAsJsonArray("outputWeights").set(0, new JsonPrimitive(4));
        artifact.addProperty("outputBias", -.1);
        final var network = PolicyNetwork.load(write(directory, artifact));
        final var rows = new float[4][RlFeatures.names().size()];
        rows[0][0] = 3;
        rows[1][0] = -3;
        rows[2][0] = 0;
        rows[3][0] = 8;
        final double[] expected = new double[2];
        for (int row = 0; row < rows.length; row++) {
            final double transformed = Math.copySign(Math.log1p(Math.abs(rows[row][0])), rows[row][0]);
            expected[row / 2] += 4 * Math.tanh(3 * (transformed - .5) / 2 + .2) - .1;
        }
        assertArrayEquals(expected, network.scores(rows, 2), 1e-12);
        assertEquals(64, network.sha256().length());
        assertThrows(IllegalArgumentException.class, () -> network.scores(rows, 3));
        rows[0][0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> network.scores(rows, 2));
    }

    @Test
    void rejectsIncompatibleOrUnsafeArtifacts(@TempDir Path directory) throws Exception {
        for (String fault : new String[]{"version", "names", "shape", "scale", "finite", "missing"}) {
            final var artifact = artifact();
            switch (fault) {
                case "version" -> artifact.addProperty("formatVersion", 2);
                case "names" -> artifact.getAsJsonArray("featureNames")
                        .set(0, new JsonPrimitive("different"));
                case "shape" -> artifact.getAsJsonArray("hiddenWeights").remove(0);
                case "scale" -> artifact.getAsJsonArray("scale")
                        .set(0, new JsonPrimitive(0));
                case "finite" -> artifact.getAsJsonArray("mean").set(0, new JsonPrimitive("NaN"));
                case "missing" -> artifact.remove("outputBias");
                default -> throw new IllegalArgumentException(fault);
            }
            final var path = write(directory, artifact);
            assertThrows(IllegalArgumentException.class, () -> PolicyNetwork.load(path), fault);
        }
    }

    @Test
    void gpuGeometryTracksDeviceFragmentationAndRemoval(@TempDir Path directory) throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 1), alloc(2, new int[]{11}, .6), alloc(3, new int[]{11}, .4),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2),
                resource(4, 3, 2), resource(5, 2, 2)));
        final var features = new RlFeatures(state);
        final var first = new Request(3);
        final var second = new Request(4);
        try (final var algorithm = new RlTop1(state, write(directory, artifact()))) {
            state.schedule(algorithm, first);
            assertEquals(1, state.placement(3).host());
            final var plan = state.placement(3);
            final var row = features.extract(first, new Assignment[][]{{plan}}, 1)[0];
            assertArrayEquals(row, features.extract(first, new Assignment[][]{{plan}}, 1, new int[]{0})[0]);
            assertEquals(.4f, row[RlFeatures.names().indexOf("host_nvidia_gpu_max_free_fraction")]);
            assertEquals(0, row[RlFeatures.names().indexOf("host_nvidia_gpu_whole_free_count")]);
            state.schedule(algorithm, second);
            assertEquals(1, state.placement(4).host());
            state.schedule(algorithm, new Request(5));
            assertEquals(2, state.placement(5).host());
            state.remove(first);
            state.remove(second);
            final var restored = features.extract(first, new Assignment[][]{{plan}}, 1)[0];
            assertArrayEquals(restored, features.extract(first, new Assignment[][]{{plan}}, 1, new int[]{0})[0]);
            assertEquals(1, restored[RlFeatures.names().indexOf("host_nvidia_gpu_max_free_fraction")]);
            assertEquals(1, restored[RlFeatures.names().indexOf("host_nvidia_gpu_whole_free_count")]);
            state.schedule(algorithm, first);
            assertNotNull(state.placement(3));
            state.remove(first);
            assertNull(state.placement(3));
        }
    }

    @Test
    void poolIdentitiesPreserveRandomStreamsMasksAndCallbacks(@TempDir Path directory) throws Exception {
        final var path = write(directory, artifact());
        for (boolean masks : new boolean[]{false, true}) {
            final var cluster = poolModel(80, 24, masks);
            final var catalogue = IntStream.rangeClosed(81, 104).mapToObj(Request::new).toArray(Request[]::new);
            for (int mode = 0; mode < 3; mode++) {
                if (mode == 2 && masks) {
                    continue;
                }
                final var expected = new Engine(cluster);
                final var actual = new Engine(cluster);
                try (final SchedulingAlgorithm reference = switch (mode) {
                    case 0 -> new RandomNFitCount(expected, 44, catalogue, 20, 10, 1);
                    case 1 -> new MaskedFitCount(expected, 44, catalogue, 20, 10, 1);
                    default -> new RlPoolFitCount(expected, 44, catalogue, 20, 64, 8, 10, 1, path);
                }; final SchedulingAlgorithm hybrid = mode == 0
                        ? new RlPoolFitCount(actual, 44, catalogue, 20, 64, 0, 10, 1, path)
                        : new RlMaskedFitCount(actual, 44, catalogue, 20, 64, mode == 1 ? 0 : 8, 10, 1, path)) {
                    for (int generation = 0; generation < 3; generation++) {
                        for (final var request : catalogue) {
                            expected.schedule(reference, request);
                            actual.schedule(hybrid, request);
                            assertEquals(expected.placement(request.resource(0)).host(),
                                    actual.placement(request.resource(0)).host(), "mode=" + mode);
                        }
                        for (final var request : catalogue) {
                            expected.remove(request);
                            actual.remove(request);
                        }
                    }
                }
            }
        }
    }

    @Test
    void sparseFeaturesMatchFullRowsAcrossBatchesAndStateChanges() throws Exception {
        final var state = new Engine(poolModel(20, 1026, false));
        final var extractor = new RlFeatures(state);
        final var request = new Request(IntStream.rangeClosed(21, 1045).toArray());
        final var plans = new Assignment[20][request.size()];
        for (int candidate = 0; candidate < plans.length; candidate++) {
            for (int member = 0; member < request.size(); member++) {
                plans[candidate][member] = new Assignment(request.resource(member), candidate + 1, 0,
                        new int[]{0}, new int[0]);
            }
        }
        final int[] selected = {0, 17, 19};
        final var blocker = new Request(1046);
        try (final var algorithm = new FitCount(state, 44, new Request[]{blocker}, 10, 1)) {
            for (int step = 0; step < 3; step++) {
                if (step == 1) {
                    state.schedule(algorithm, blocker);
                } else if (step == 2) {
                    state.remove(blocker);
                }
                final var full = extractor.extract(request, plans, plans.length);
                final var sparse = extractor.extract(request, plans, plans.length, selected);
                assertEquals(selected.length * request.size(), sparse.length);
                for (int candidate = 0; candidate < selected.length; candidate++) {
                    for (int member = 0; member < request.size(); member++) {
                        assertArrayEquals(full[selected[candidate] * request.size() + member],
                                sparse[candidate * request.size() + member]);
                    }
                }
                assertEquals(20f, sparse[0][RlFeatures.names().indexOf("candidate_count")]);
                assertEquals(100f, sparse[0][RlFeatures.names().indexOf("host_capacity_vcores")]);
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(request, plans, plans.length, new int[]{2, 1}));
    }

    @Test
    void poolFastPathsAndRowGateAvoidInferenceAndKeepBaselineDraws(@TempDir Path directory) throws Exception {
        final var context = new Engine(poolModel(80, 373, false));
        final var path = write(directory, artifact());
        final var selector = new RlPoolSelector(context, 44, 20, 64, 8, path, false);
        final var reference = new CandidateSampler(44);
        final var auxiliary = new SplittableRandom(44 ^ 0xBB67AE8584CAA73BL);
        final var single = new Request(81);
        for (int count : new int[]{21, 28, 80}) {
            final var plans = new Assignment[count][1];
            for (int index = 0; index < count; index++) {
                plans[index][0] = new Assignment(81, index + 1, 0, new int[]{0}, new int[0]);
            }
            final var random = reference.sample(count, 20);
            final var selected = selector.select(single, plans, count);
            final var pool = reference.sampleRemaining(Math.min(44, count - 20), auxiliary);
            final var expected = Arrays.copyOf(random, Math.min(count, 28));
            System.arraycopy(pool, 0, expected, random.length, expected.length - random.length);
            Arrays.sort(expected);
            assertArrayEquals(expected, selected);
        }
        assertEquals(1L, selector.counters().get("inference_calls"));
        assertEquals(44L, selector.counters().get("ml_feature_rows"));
        final var wide = new Request(IntStream.rangeClosed(81, 453).toArray());
        final var plans = new Assignment[80][];
        for (int index = 0; index < plans.length; index++) {
            plans[index] = new Assignment[]{new Assignment(81, index + 1, 0, new int[]{0}, new int[0])};
        }
        assertArrayEquals(reference.sample(80, 20), selector.select(wide, plans, 80));
        assertEquals(1L, selector.counters().get("inference_row_budget"));
        assertEquals(1L, selector.counters().get("inference_calls"));
        final var uniform = new RlPoolSelector(context, 44, 20, 64, 8, path, true);
        assertArrayEquals(new CandidateSampler(44).sample(80, 20), uniform.select(wide, plans, 80));
        assertEquals(1L, uniform.counters().get("inference_row_budget"));
        assertEquals(0L, uniform.counters().get("auxiliary_pool_plans"));
        final var random = reference.sample(80, 20);
        final var pool = reference.sampleRemaining(44, auxiliary);
        final var expected = Arrays.copyOf(random, 28);
        System.arraycopy(pool, 0, expected, 20, 8);
        Arrays.sort(expected);
        assertArrayEquals(expected, selector.select(single, plans, 80));
        final var overflowSelector = new RlPoolSelector(context, 44, 20, 22, 1, path, false);
        final var overflowReference = new CandidateSampler(44);
        final var overflowPlans = new Assignment[262145][];
        Arrays.fill(overflowPlans, plans[0]);
        assertArrayEquals(overflowReference.sample(overflowPlans.length, 20),
                overflowSelector.select(new Request(IntStream.rangeClosed(1, 8192).toArray()),
                        overflowPlans, overflowPlans.length));
        assertEquals(1L, overflowSelector.counters().get("inference_row_budget"));
        assertEquals(0L, overflowSelector.counters().get("auxiliary_pool_plans"));
        assertTrue(RlPoolSelector.rowGated(Integer.MAX_VALUE, 1, 2));
        assertEquals(false, RlPoolSelector.rowGated(20, 3, 1025));
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> new RlPoolFitCount(context, 44, new Request[0], 20, 64, 0, 10, 1,
                        directory.resolve("missing.json")));
    }

    @Test
    void maskProfileGateKeepsTheSameRlSelectorAndCompletePhysicalCatalogue(@TempDir Path directory) throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 10000));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        for (int host = 1; host <= 3; host++) {
            records.add(resource(host, 1, 1));
        }
        final var catalogue = new Request[4097];
        for (int index = 0; index < catalogue.length; index++) {
            records.add(alloc(index + 2, new int[]{1}, 1 + index * .001));
            records.add(resource(index + 4, index + 2, 2));
            catalogue[index] = new Request(index + 4);
        }
        final var cluster = model(records.toArray(Record[]::new));
        final var path = write(directory, artifact());
        for (boolean uniform : new boolean[]{false, true}) {
            final var expected = new Engine(cluster);
            final var actual = new Engine(cluster);
            try (final var reference = new RlPoolFitCount(expected, 44, catalogue, 1, 3, 1, 1, 1, path, uniform);
                    final var hybrid = new RlMaskedFitCount(actual, 44, catalogue, 1, 3, 1, 1, 1, path, uniform)) {
                assertEquals("false", hybrid.parameters().get("masked_enabled"));
                assertEquals("profile_limit", hybrid.parameters().get("masked_disabled_reason"));
                assertEquals(4097L, hybrid.counters().get("physical_families"));
                for (int generation = 0; generation < 3; generation++) {
                    expected.schedule(reference, catalogue[0]);
                    actual.schedule(hybrid, catalogue[0]);
                    assertEquals(expected.placement(4).host(), actual.placement(4).host());
                    expected.remove(catalogue[0]);
                    actual.remove(catalogue[0]);
                }
                assertEquals(reference.counters().get("inference_calls"), hybrid.counters().get("inference_calls"));
                assertEquals(uniform ? 0L : 3L, hybrid.counters().get("inference_calls"));
            }
        }
    }

    private static ClusterSpec poolModel(int hosts, int pods, boolean masks) throws Exception {
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1, 2}, 100, 100));
        records.add(alloc(2, new int[]{1, 2}, 1, 2));
        records.add(alloc(3, new int[]{1, 2}, 2, 1));
        records.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        records.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setCandidates(masks ? 2 : 0)));
        records.add(rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setCandidates(masks ? 3 : 0)));
        records.add(rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)));
        if (masks) {
            records.add(candidates(2, IntStream.rangeClosed(1, 21).toArray()));
            records.add(candidates(3, IntStream.rangeClosed(1, 28).toArray()));
        }
        for (int host = 1; host <= hosts; host++) {
            records.add(resource(host, 1, 1));
        }
        for (int pod = 0; pod < pods; pod++) {
            records.add(resource(hosts + pod + 1, 2 + pod % 2, 2 + pod % 3));
        }
        return model(records.toArray(Record[]::new));
    }

    private static Path write(Path directory, JsonObject artifact) throws Exception {
        final var path = directory.resolve("policy.json");
        Files.writeString(path, new Gson().toJson(artifact));
        return path;
    }

    private static JsonObject artifact() {
        final int features = RlFeatures.names().size();
        final var scale = new double[features];
        Arrays.fill(scale, 1);
        final var json = new Gson();
        final var result = new JsonObject();
        result.addProperty("formatVersion", 1);
        result.add("featureNames", json.toJsonTree(RlFeatures.names()));
        result.add("mean", json.toJsonTree(new double[features]));
        result.add("scale", json.toJsonTree(scale));
        result.add("hiddenWeights", json.toJsonTree(new double[32][features]));
        result.add("hiddenBias", json.toJsonTree(new double[32]));
        result.add("outputWeights", json.toJsonTree(new double[32]));
        result.addProperty("outputBias", 0);
        return result;
    }
}
