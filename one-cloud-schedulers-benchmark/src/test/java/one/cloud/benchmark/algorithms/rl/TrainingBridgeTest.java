package one.cloud.benchmark.algorithms.rl;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.runner.BenchmarkExperiment;
import one.cloud.benchmark.runner.Scenario;
import one.cloud.benchmark.simulator.ManualScenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingBridgeTest {
    @TempDir
    Path temporary;

    @Test
    void teacherMatchesNativeFitCountThroughGpuRemovalAndRecreation() throws Exception {
        final var config = scenario();
        final var source = write(config);
        final var compiled = ManualScenario.compile(config);
        final var scenario = Scenario.fromAtomicEvents(compiled.model(), compiled.requests(), compiled.events(),
                compiled.catalogue(), compiled.digest());
        final var baseline = BenchmarkExperiment.measure(scenario,
                new BenchmarkExperiment.Options(0, 32, 1, 0, 42),
                (context, ignored, catalogue) -> new FitCount(context, 42, catalogue, 10, 64));
        final var messages = execute(source, "teacher", true, 0);
        final var result = messages.getLast();
        final var metrics = result.getAsJsonObject("metrics");
        final var measured = metrics.getAsJsonObject("measurement").getAsJsonObject("counts");
        assertEquals(baseline.counts().checksum(), measured.get("checksum").getAsString());
        assertEquals(baseline.counts().admittedResources(), measured.get("admittedResources").getAsLong());
        assertEquals(3, metrics.get("pods").getAsInt());
        assertEquals(3, metrics.get("gpuPods").getAsInt());
        assertEquals(1, metrics.get("shards").getAsInt());
        assertEquals(4, metrics.get("attemptedPods").getAsInt());
        assertEquals(measured.get("checksum"), metrics.getAsJsonObject("quality")
                .getAsJsonObject("counts").get("checksum"));
    }

    @Test
    void rewardsIncludeSingletonPlacementsAndFlushAtTermination() throws Exception {
        final var messages = execute(write(scenario()), 0, false, 0);
        long reward = 0;
        int decisions = 0;
        for (final var message : messages) {
            if (message.has("reward")) {
                reward += message.get("reward").getAsLong();
            }
            if (message.get("type").getAsString().equals("decision")) {
                decisions++;
                final int members = message.get("members").getAsInt();
                assertTrue(message.getAsJsonArray("features").size() > members);
                assertEquals(RlFeatures.names().size(), message.getAsJsonArray("features")
                        .get(0).getAsJsonArray().size());
            }
        }
        assertEquals(1, decisions);
        assertEquals(3, reward);
        assertEquals(0, messages.getLast().get("action").getAsInt());
        assertEquals(3, messages.getLast().get("reward").getAsLong());
    }

    @Test
    void invalidAndFractionalActionsFailWithoutAResult() throws Exception {
        final var source = write(scenario());
        for (final var action : List.of(-1, 999, 0.5, "0")) {
            final var messages = execute(source, action, false, 1);
            assertEquals("error", messages.getLast().get("type").getAsString());
            assertTrue(messages.stream().noneMatch(message -> message.get("type").getAsString().equals("result")));
        }
    }

    @Test
    void baselineUsesRegistryAndDoesNotRequestExternalActions() throws Exception {
        final var source = write(scenario());
        final var output = new StringWriter();
        final String command = new Gson().toJson(Map.of("input", source.toString(), "algorithm", "fitcount"));
        assertEquals(0, TrainingBridge.run(new BufferedReader(new StringReader(command + "\n")),
                new PrintWriter(output)));
        final var messages = output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
        assertEquals(2, messages.size());
        assertEquals("ready", messages.getFirst().get("type").getAsString());
        assertEquals("result", messages.getLast().get("type").getAsString());
        assertEquals(3, messages.getLast().getAsJsonObject("metrics").get("pods").getAsInt());
    }

    private List<JsonObject> execute(Path source, Object action, boolean quality, int expectedStatus) {
        final var output = new StringWriter();
        final var json = new Gson();
        final var input = new BufferedReader(new StringReader("")) {
            private boolean started;

            @Override
            public String readLine() {
                if (!started) {
                    started = true;
                    return json.toJson(Map.of("input", source.toString(), "quality", quality));
                }
                if (output.toString().contains("\"type\":\"result\"")) {
                    return null;
                }
                return json.toJson(Map.of("action", action));
            }
        };
        assertEquals(expectedStatus, TrainingBridge.run(input, new PrintWriter(output)));
        return output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private Path write(ManualScenario.Config config) throws Exception {
        final var source = temporary.resolve("scenario.json");
        Files.writeString(source, new Gson().toJson(config));
        return source;
    }

    private static ManualScenario.Config scenario() {
        final var capacity = Map.of("VCORES", 1.0, "NVIDIA_GPUS", 1.0);
        final var demand = Map.of("VCORES", 1.0, "NVIDIA_GPUS", 1.0);
        final var hosts = List.of(new ManualScenario.Host("a", capacity, List.of()),
                new ManualScenario.Host("b", capacity, List.of()));
        final var pod = new ManualScenario.Member("pod", "POD", demand, null, null, null);
        final var shard = new ManualScenario.Member("shard", "SHARD", Map.of(), null, null, null);
        final var requests = List.of(new ManualScenario.Unit("first", List.of(pod, shard)),
                new ManualScenario.Unit("second", List.of(new ManualScenario.Member("pod2", "POD", demand,
                        null, null, null))),
                new ManualScenario.Unit("third", List.of(new ManualScenario.Member("pod3", "POD", demand,
                        null, null, null))));
        final var events = List.of(new ManualScenario.Event("create", "first"),
                new ManualScenario.Event("create", "second"), new ManualScenario.Event("create", "third"),
                new ManualScenario.Event("delete", "second"), new ManualScenario.Event("create", "second"));
        return new ManualScenario.Config(hosts, requests, events, List.of("first", "second", "third"));
    }
}
