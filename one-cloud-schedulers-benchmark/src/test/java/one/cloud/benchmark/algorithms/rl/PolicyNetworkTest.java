package one.cloud.benchmark.algorithms.rl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.simulator.ManualScenario;
import one.cloud.benchmark.simulator.SimulatorSession;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
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
            assertEquals(.4f, row[RlFeatures.names().indexOf("host_nvidia_gpu_max_free_fraction")]);
            assertEquals(0, row[RlFeatures.names().indexOf("host_nvidia_gpu_whole_free_count")]);
            state.schedule(algorithm, second);
            assertEquals(1, state.placement(4).host());
            state.schedule(algorithm, new Request(5));
            assertEquals(2, state.placement(5).host());
            state.remove(first);
            state.remove(second);
            final var restored = features.extract(first, new Assignment[][]{{plan}}, 1)[0];
            assertEquals(1, restored[RlFeatures.names().indexOf("host_nvidia_gpu_max_free_fraction")]);
            assertEquals(1, restored[RlFeatures.names().indexOf("host_nvidia_gpu_whole_free_count")]);
            state.schedule(algorithm, first);
            assertNotNull(state.placement(3));
            state.remove(first);
            assertNull(state.placement(3));
        }
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
