package one.cloud.benchmark.simulator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.profiling.AsyncProfiler;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SimulatorServerTest {
    @TempDir
    Path temporary;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void launchRejectsInvalidArgumentsBeforeStartingAServer() {
        for (final var arguments : new String[][]{
                {"--port"}, {"--port", "not-a-number"}, {"--port", "-1"}, {"--port", "65536"},
                {"--port", "0", "--port", "0"}, {"--no-open", "--no-open"}, {"--unknown"}}) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            try (final var out = new PrintStream(output, true, StandardCharsets.UTF_8);
                    final var err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
                assertThrows(IllegalArgumentException.class, () -> SimulatorServer.launch(arguments, out, err));
            }
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void refusesCrossSiteMutationsAndNeverServesArbitraryFiles() throws Exception {
        try (final var server = new SimulatorServer(0)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            assertEquals(403, send(base + "/api/reset", "POST", "{}", false, null).statusCode());
            assertEquals(403, send(base + "/api/benchmark", "POST", "{}", false, null).statusCode());
            for (final var endpoint : List.of("datasets", "run", "profile", "cancel")) {
                assertEquals(403, send(base + "/api/experiments/" + endpoint, "POST", "{}", false, null)
                        .statusCode(), endpoint);
            }
            assertEquals(403, send(base + "/api/reset", "POST", "{}", true,
                    "https://untrusted.example").statusCode());
            assertEquals(404, send(base + "/AGENTS.md", "GET", null, false, null).statusCode());
            assertEquals(404, send(base + "/../AGENTS.md", "GET", null, false, null).statusCode());
            assertEquals(405, send(base + "/api/reset", "GET", null, false, null).statusCode());
        }
    }

    @Test
    void exposesAnIndependentBenchmarkWorkspaceWithNoUploadedDatasets() throws Exception {
        try (final var server = new SimulatorServer(0, null)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var response = send(base + "/api/experiments", "GET", null, false, null);
            assertEquals(200, response.statusCode(), response.body());
            final var workspace = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(workspace.getAsJsonArray("datasets").isEmpty());
            assertTrue(workspace.get("job").isJsonNull());
            assertFalse(workspace.get("profileAvailable").getAsBoolean());
            assertFalse(workspace.get("flameGraphAvailable").getAsBoolean());

            final var page = send(base + "/benchmarks", "GET", null, false, null);
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            assertFalse(page.body().isBlank());
        }
    }

    @Test
    void runsAnUploadedDatasetInIsolatedJvmsWithoutMovingTheLiveSimulatorCursor() throws Exception {
        withCliClasspath(() -> {
            final Path directory;
            try (final var server = new SimulatorServer(0, null)) {
                server.start();
                final var base = "http://127.0.0.1:" + server.port();
                assertEquals(200, send(base + "/api/step", "POST", "{\"count\":3}", true, null).statusCode());
                final var dataset = uploadExperimentDataset(base);
                assertEquals("./tiny-dataset", dataset.get("directory").getAsString());
                final var request = experimentRequest(dataset.get("id").getAsString());
                final var started = send(base + "/api/experiments/run", "POST", request.toString(), true, null);
                assertEquals(202, started.statusCode(), started.body());
                final var id = JsonParser.parseString(started.body()).getAsJsonObject().get("id").getAsString();
                final var job = awaitExperimentJob(base, id);
                assertEquals("complete", job.get("status").getAsString(), job.toString());
                assertEquals("benchmark", job.get("kind").getAsString());
                final var report = job.getAsJsonObject("report");
                assertEquals(1, report.get("formatVersion").getAsInt());
                assertEquals("isolated-jvm-full-replay", report.get("measurement").getAsString());
                assertEquals(1, report.get("forks").getAsInt());
                assertEquals(0, report.get("warmupsPerFork").getAsInt());
                assertEquals(1, report.get("measurementsPerFork").getAsInt());
                assertEquals(1, report.getAsJsonArray("cases").size());
                final var cell = report.getAsJsonArray("cases").get(0).getAsJsonObject();
                assertEquals(dataset.get("input"), cell.get("input"));
                directory = Path.of(cell.get("dataset").getAsString());
                assertTrue(Files.isRegularFile(directory.resolve("snapshot.pb")));
                assertTrue(Files.isRegularFile(directory.resolve("operations.pb")));
                assertEquals(7, cell.getAsJsonObject("algorithm").getAsJsonObject("parameters").get("seed").getAsInt());
                assertEquals(4, cell.getAsJsonObject("scenario").get("normalizedOperations").getAsInt());
                final var fork = cell.getAsJsonArray("forks").get(0).getAsJsonObject();
                assertTrue(fork.get("error").isJsonNull(), fork.toString());
                assertEquals(1, fork.getAsJsonArray("measurements").size());
                final var measurement = fork.getAsJsonArray("measurements").get(0).getAsJsonObject();
                assertTrue(measurement.get("replayNanos").getAsLong() > 0);
                final long p90 = measurement.getAsJsonObject("scheduleWindows").get("p90Nanos").getAsLong();
                assertTrue(p90 > 0);
                assertEquals(p90, cell.getAsJsonObject("timing").getAsJsonObject("scheduleP90Nanos")
                        .get("median").getAsLong());
                assertEquals(4, measurement.getAsJsonObject("counts").get("processedOperations").getAsInt());
                assertTrue(measurement.getAsJsonObject("counts").get("complete").getAsBoolean());
                final var quality = cell.getAsJsonObject("quality");
                final var counts = quality.getAsJsonObject("counts");
                assertEquals(2, counts.get("attemptedUnits").getAsLong());
                assertEquals(2, counts.get("admittedUnits").getAsLong());
                assertEquals(2, counts.get("admittedResources").getAsLong());
                assertEquals(2, counts.get("committedRemovals").getAsLong());
                assertFalse(quality.getAsJsonArray("checkpoints").isEmpty());
                assertTrue(quality.getAsJsonObject("peakActiveLogical").get("VCORES").getAsDouble() > 0);

                final var workspace = JsonParser.parseString(send(base + "/api/experiments", "GET", null,
                        false, null).body()).getAsJsonObject();
                assertEquals(id, workspace.getAsJsonObject("benchmark").get("id").getAsString());
                final var profile = profileRequest(dataset, cell, report.getAsJsonObject("options"));
                assertEquals(400, send(base + "/api/experiments/profile", "POST", profile.toString(), true, null)
                        .statusCode());
                assertEquals(400, send(base + "/api/experiments/artifact?id=" + id, "GET", null, false, null)
                        .statusCode());
                final var state = send(base + "/api/state?summary=1", "GET", null, false, null);
                assertEquals(3, JsonParser.parseString(state.body()).getAsJsonObject().get("cursor").getAsInt());
            }
            assertFalse(Files.exists(directory));
            return null;
        });
    }

    @Test
    void refusesUnknownDatasetPathsAndExternalAlgorithmFilesBeforeStartingAnExperiment() throws Exception {
        try (final var server = new SimulatorServer(0, null)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var dataset = uploadExperimentDataset(base);
            final var id = dataset.get("id").getAsString();
            final var outside = experimentRequest("../not-an-upload");
            assertEquals(400, send(base + "/api/experiments/run", "POST", outside.toString(), true, null)
                    .statusCode());
            for (final var selection : List.of(
                    "{\"id\":\"missing\"}",
                    "{\"id\":\"rollout\"}",
                    "{\"id\":\"rl-top1\",\"parameters\":{\"model\":\"/tmp/external-model.json\"}}",
                    "{\"id\":\"rl-top1\",\"parameters\":{\"model\":\" \"}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"fitCap\":0}}")) {
                final var request = experimentRequest(id);
                final var algorithms = new JsonArray();
                algorithms.add(JsonParser.parseString(selection));
                request.add("algorithms", algorithms);
                final var response = send(base + "/api/experiments/run", "POST", request.toString(), true, null);
                assertEquals(400, response.statusCode(), selection + " -> " + response.body());
            }
            assertEquals(400, send(base + "/api/experiments/cancel", "POST", "{\"id\":\"missing\"}", true, null)
                    .statusCode());
            assertEquals(400, send(base + "/api/experiments/job?id=missing", "GET", null, false, null).statusCode());
            assertEquals(400, send(base + "/api/experiments/artifact?id=missing", "GET", null, false, null)
                    .statusCode());
            final var workspace = JsonParser.parseString(send(base + "/api/experiments", "GET", null,
                    false, null).body()).getAsJsonObject();
            assertTrue(workspace.get("job").isJsonNull());
            assertEquals(1, workspace.getAsJsonArray("datasets").size());
        }
    }

    @Test
    void retainsTheCliReportWhenOneBenchmarkCaseFails() throws Exception {
        withCliClasspath(() -> {
            try (final var server = new SimulatorServer(0, null)) {
                server.start();
                final var base = "http://127.0.0.1:" + server.port();
                final var dataset = uploadExperimentDataset(base);
                final var request = experimentRequest(dataset.get("id").getAsString());
                request.addProperty("gpuModel", "aggregate");
                request.add("algorithms", JsonParser.parseString("[{\"id\":\"fitcount\"},{\"id\":\"fgd\"}]"));
                final var started = send(base + "/api/experiments/run", "POST", request.toString(), true, null);
                assertEquals(202, started.statusCode(), started.body());
                final var id = JsonParser.parseString(started.body()).getAsJsonObject().get("id").getAsString();
                final var job = awaitExperimentJob(base, id);
                assertEquals("partial", job.get("status").getAsString(), job.toString());
                final var report = job.getAsJsonObject("report");
                assertEquals("isolated-jvm-full-replay", report.get("measurement").getAsString());
                assertEquals(2, report.getAsJsonArray("cases").size());
                final var successful = report.getAsJsonArray("cases").get(0).getAsJsonObject();
                assertEquals(2, successful.getAsJsonObject("quality").getAsJsonObject("counts")
                        .get("admittedUnits").getAsLong());
                final var failed = report.getAsJsonArray("cases").get(1).getAsJsonObject();
                assertEquals("fgd", failed.getAsJsonObject("algorithm").get("id").getAsString());
                assertTrue(failed.get("quality").isJsonNull());
                assertFalse(failed.get("qualityError").isJsonNull());
                assertTrue(failed.getAsJsonArray("forks").get(0).getAsJsonObject().get("error").getAsString()
                        .contains("devices20"));
                assertEquals(0, failed.getAsJsonObject("timing").getAsJsonObject("replayNanos")
                        .get("samples").getAsInt());
            }
            return null;
        });
    }

    @Test
    void refusesProfileUploadsAndScenarioDigestsThatDoNotMatchTheSelectedCase() throws Exception {
        final var bin = Files.createDirectory(temporary.resolve("bin"));
        final var asprof = bin.resolve("asprof");
        Files.writeString(asprof, "#!/bin/sh\nexit 99\n");
        assertTrue(asprof.toFile().setExecutable(true));
        try (final var server = new SimulatorServer(0, new AsyncProfiler(temporary))) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var dataset = uploadExperimentDataset(base);
            final var profile = JsonParser.parseString("""
                    {"algorithm":{"id":"fitcount"},"scenario":{"digest":"stale","gpuModel":"devices20",
                     "bulkSize":1},"options":{"maxOperations":0,"catalogueLimit":2,"probeCap":1},
                     "event":"cpu","format":"jfr","timeoutSeconds":30}
                    """).getAsJsonObject();
            profile.addProperty("datasetId", dataset.get("id").getAsString());
            profile.add("input", dataset.get("input").deepCopy());
            profile.getAsJsonObject("input").addProperty("snapshotSha256", "wrong");
            assertEquals(400, send(base + "/api/experiments/profile", "POST", profile.toString(), true, null)
                    .statusCode());
            final var before = JsonParser.parseString(send(base + "/api/experiments", "GET", null,
                    false, null).body()).getAsJsonObject();
            assertTrue(before.get("profileAvailable").getAsBoolean());
            assertTrue(before.get("job").isJsonNull());
            profile.add("input", dataset.get("input").deepCopy());
            final var started = send(base + "/api/experiments/profile", "POST", profile.toString(), true, null);
            assertEquals(202, started.statusCode(), started.body());
            final var id = JsonParser.parseString(started.body()).getAsJsonObject().get("id").getAsString();
            final var job = awaitExperimentJob(base, id);
            assertEquals("failed", job.get("status").getAsString(), job.toString());
            assertTrue(job.get("error").getAsString().contains("scenario"));
            assertEquals(400, send(base + "/api/experiments/artifact?id=" + id, "GET", null, false, null)
                    .statusCode());
        }
    }

    @Test
    void cancelsAnExperimentBeforeAllowingAnotherOneToStart() throws Exception {
        withCliClasspath(() -> {
            try (final var server = new SimulatorServer(0, null)) {
                server.start();
                final var base = "http://127.0.0.1:" + server.port();
                final var dataset = uploadExperimentDataset(base);
                final var request = experimentRequest(dataset.get("id").getAsString());
                request.addProperty("forks", 20);
                final var started = send(base + "/api/experiments/run", "POST", request.toString(), true, null);
                assertEquals(202, started.statusCode(), started.body());
                final var id = JsonParser.parseString(started.body()).getAsJsonObject().get("id").getAsString();
                final var worker = awaitBenchmarkWorker();
                assertEquals(409, send(base + "/api/experiments/run", "POST", request.toString(), true, null)
                        .statusCode());
                assertEquals(400, send(base + "/api/experiments/cancel", "POST", "{\"id\":\"wrong\"}", true, null)
                        .statusCode());
                final var cancelled = send(base + "/api/experiments/cancel", "POST", "{\"id\":\"" + id + "\"}",
                        true, null);
                assertEquals(200, cancelled.statusCode(), cancelled.body());
                final var stopped = awaitExperimentJob(base, id);
                assertEquals("cancelled", stopped.get("status").getAsString(), stopped.toString());
                assertFalse(worker.isAlive(), "The cancelled benchmark worker is still running");
                request.addProperty("forks", 1);
                final var restarted = send(base + "/api/experiments/run", "POST", request.toString(), true, null);
                assertEquals(202, restarted.statusCode(), restarted.body());
                final var nextId = JsonParser.parseString(restarted.body()).getAsJsonObject().get("id").getAsString();
                assertEquals("complete", awaitExperimentJob(base, nextId).get("status").getAsString());
            }
            return null;
        });
    }

    @Test
    void benchmarksTheCurrentManualScenarioWithoutMovingTheSimulatorCursor() throws Exception {
        try (final var server = new SimulatorServer(0)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            assertEquals(200, send(base + "/api/step", "POST", "{\"count\":3}", true, null).statusCode());
            assertEquals(409, send(base + "/api/benchmark", "POST", "{\"scenarioDigest\":\"stale\"}",
                    true, null).statusCode());
            final var started = send(base + "/api/benchmark", "POST", """
                    {"maxOperations": 2, "inflationAttempts": 0, "algorithms": [{"id": "geometric-best-fit"}]}
                    """, true, null);
            assertEquals(202, started.statusCode(), started.body());
            final var id = JsonParser.parseString(started.body()).getAsJsonObject().get("id").getAsString();
            final var report = awaitBenchmark(base, id);
            assertEquals("complete", report.get("status").getAsString());
            assertEquals("manual", report.get("source").getAsString());
            final var quality = report.getAsJsonArray("lanes").get(0).getAsJsonObject().getAsJsonObject("quality");
            assertEquals(2, quality.getAsJsonObject("counts").get("processedOperations").getAsInt());
            assertFalse(quality.getAsJsonObject("counts").get("complete").getAsBoolean());
            assertTrue(quality.getAsJsonArray("checkpoints").asList().stream()
                    .anyMatch(point -> point.getAsJsonObject().get("operation").getAsInt() == 2));
            final var state = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertTrue(state.body().contains("\"cursor\":3"), state.body());
        }
    }

    private JsonObject awaitBenchmark(String base, String id) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final var polled = send(base + "/api/benchmark?id=" + id, "GET", null, false, null);
            assertEquals(200, polled.statusCode(), polled.body());
            final var report = JsonParser.parseString(polled.body()).getAsJsonObject();
            if (!report.get("status").getAsString().equals("running")) {
                return report;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Benchmark job timed out");
    }

    @Test
    void validatesBodiesAndParametersWithoutLosingTheCurrentSession() throws Exception {
        try (final var server = new SimulatorServer(0)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            assertEquals(400, send(base + "/api/step", "POST", "{", true, null).statusCode());
            assertEquals(400, send(base + "/api/step", "POST", "{\"count\":101}", true, null).statusCode());
            assertEquals(400, send(base + "/api/state?hostLimit=1000000", "GET", null, false, null).statusCode());
            assertEquals(400, send(base + "/api/dataset", "POST", "not protobuf", true, null).statusCode());
            for (final var endpoint : List.of("/api/step", "/api/profile")) {
                for (final var body : List.of("{\"all\":\"true\"}", "{\"all\":null}", "{\"all\":1}",
                        "{\"all\":true,\"count\":1}", "{\"all\":false,\"count\":1}")) {
                    assertEquals(400, send(base + endpoint, "POST", body, true, base).statusCode(), endpoint + body);
                }
            }
            final var state = send(base + "/api/state", "GET", null, false, null);
            assertEquals(200, state.statusCode(), state.body());
            assertTrue(state.headers().firstValue("Content-Type").orElse("").contains("application/json"));
            assertTrue(state.body().contains("\"cursor\":0"), state.body());
            final var stepped = send(base + "/api/step", "POST", "{\"count\":1}", true, base);
            assertEquals(200, stepped.statusCode(), stepped.body());
            assertTrue(stepped.body().contains("\"cursor\":1"), stepped.body());
            assertEquals(400, send(base + "/api/manual", "POST",
                    "{\"config\":{\"hosts\":[{\"name\":\"bad\",\"resources\":{\"VCORES\":-1}}]}}",
                    true, base).statusCode());
            assertEquals(400, send(base + "/api/manual", "POST",
                    "{\"config\":{\"hosts\":[null]}}", true, base).statusCode());
            assertEquals(400, send(base + "/api/algorithms", "POST",
                    "{\"algorithms\":[{\"id\":\"missing-algorithm\"}]}", true, base).statusCode());
            final var preserved = send(base + "/api/state", "GET", null, false, null);
            assertEquals(200, preserved.statusCode(), preserved.body());
            assertTrue(preserved.body().contains("\"cursor\":1"), preserved.body());
            assertEquals(200, send(base + "/api/reset", "POST", "{}", true, base).statusCode());
        }
    }

    @Test
    void runsAllRemainingEventsWithoutProfiling() throws Exception {
        try (final var server = new SimulatorServer(0, null)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            assertEquals(200, send(base + "/api/step", "POST", "{\"count\":1}", true, base).statusCode());
            final var response = send(base + "/api/step?summary=1", "POST", "{\"all\":true}", true, base);
            assertEquals(200, response.statusCode(), response.body());
            final var state = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(30, state.get("cursor").getAsInt());
            assertEquals(state.get("totalEvents"), state.get("cursor"));
            final var repeated = send(base + "/api/step?summary=1", "POST", "{\"all\":true}", true, base);
            assertEquals(200, repeated.statusCode(), repeated.body());
            assertEquals(state, JsonParser.parseString(repeated.body()));
        }
    }

    @Test
    void exposesOnlyRelevantSettingsAndRejectsInvalidParametersWithoutChangingState() throws Exception {
        try (final var server = new SimulatorServer(0)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var descriptors = JsonParser.parseString(send(base + "/api/algorithms", "GET", null,
                    false, null).body()).getAsJsonObject().getAsJsonArray("algorithms");
            boolean foundTop1 = false;
            for (final var descriptor : descriptors) {
                final var algorithm = descriptor.getAsJsonObject();
                if (algorithm.get("id").getAsString().equals("catboost-top1")) {
                    foundTop1 = true;
                    assertEquals(0, algorithm.getAsJsonArray("parameters").size());
                }
            }
            assertTrue(foundTop1);
            assertEquals(200, send(base + "/api/step", "POST", "{\"count\":1}", true, null).statusCode());
            for (final var invalid : new String[]{
                    "{\"id\":\"fitcount\",\"seed\":42}",
                    "{\"id\":1}",
                    "{\"id\":\"fitcount\",\"parameters\":null}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"seed\":0.5}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"seed\":9007199254740992}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"fitCap\":0}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"seed\":\"44\"}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"seed\":{}}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"seed\":null}}",
                    "{\"id\":\"fitcount\",\"parameters\":{\"unknown\":1}}",
                    "{\"id\":\"catboost-top1\",\"parameters\":{\"seed\":44}}"}) {
                final var response = send(base + "/api/algorithms", "POST",
                        "{\"algorithms\":[" + invalid + "]}", true, null);
                assertEquals(400, response.statusCode(), invalid + " -> " + response.body());
            }
            final var preserved = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertTrue(preserved.body().contains("\"cursor\":1"), preserved.body());
            final var changed = send(base + "/api/algorithms", "POST",
                    "{\"algorithms\":[{\"id\":\"fitcount\",\"parameters\":{\"seed\":7}}]}", true, null);
            assertEquals(200, changed.statusCode(), changed.body());
            assertEquals(0, JsonParser.parseString(changed.body()).getAsJsonObject().get("cursor").getAsInt());
            final var parameters = JsonParser.parseString(changed.body()).getAsJsonObject()
                    .getAsJsonArray("lanes").get(0).getAsJsonObject()
                    .getAsJsonObject("algorithm").getAsJsonObject("parameters");
            assertEquals(7, parameters.get("seed").getAsInt());
            assertEquals(10, parameters.get("fitCap").getAsInt());
            assertEquals(64, parameters.get("cacheRows").getAsInt());
            assertFalse(parameters.has("topN"));
        }
    }

    @Test
    void importsTheNativeDatasetAndDrainsTheSameAtomicTrace() throws Exception {
        writeTrace(temporary, new Record[]{
                alloc(1, new int[]{1, 2}, 4, 4096), alloc(2, new int[]{1, 2}, 2, 1024),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2)}, 2, 3, -3, -2);
        final byte[] snapshot = Files.readAllBytes(temporary.resolve("snapshot.pb"));
        final byte[] operations = Files.readAllBytes(temporary.resolve("operations.pb"));
        try (final var server = new SimulatorServer(0)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var request = HttpRequest.newBuilder(URI.create(base + "/api/dataset"))
                    .header("X-Placement-UI", "1")
                    .header("X-Snapshot-Length", Integer.toString(snapshot.length))
                    .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(snapshot),
                            HttpRequest.BodyPublishers.ofByteArray(operations)))
                    .build();
            final var imported = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, imported.statusCode(), imported.body());
            assertTrue(imported.body().contains("\"source\":\"dataset\""), imported.body());
            assertTrue(imported.body().contains("\"rawOperations\":4"), imported.body());
            final var drained = send(base + "/api/step", "POST", "{\"all\":true}", true, null);
            assertEquals(200, drained.statusCode(), drained.body());
            assertTrue(drained.body().contains("\"cursor\":4"), drained.body());
            final var lane = JsonParser.parseString(drained.body()).getAsJsonObject()
                    .getAsJsonArray("lanes").get(0).getAsJsonObject();
            final var totals = lane.getAsJsonObject("counters");
            assertEquals(2, totals.get("placedPods").getAsInt());
            assertEquals(2, totals.get("removedPods").getAsInt());
            assertEquals(0, totals.get("activePods").getAsInt());
            assertEquals(2, lane.getAsJsonObject("latency").getAsJsonObject("create").get("count").getAsInt());
            assertEquals(2, lane.getAsJsonObject("latency").getAsJsonObject("remove").get("count").getAsInt());
            final var scenario = send(base + "/api/scenario", "GET", null, false, null);
            assertEquals("{\"config\":null}", scenario.body());
            final var summary = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertEquals(200, summary.statusCode(), summary.body());
            assertTrue(summary.body().contains("\"cursor\":4"), summary.body());
            assertFalse(summary.body().contains("\"lanes\""), summary.body());
            final var started = send(base + "/api/benchmark", "POST", """
                    {"inflationAttempts": 0, "algorithms": [{"id": "geometric-best-fit"}]}
                    """, true, null);
            assertEquals(202, started.statusCode(), started.body());
            final var report = awaitBenchmark(base, JsonParser.parseString(started.body()).getAsJsonObject()
                    .get("id").getAsString());
            assertEquals("complete", report.get("status").getAsString());
            assertEquals("dataset", report.get("source").getAsString());
            assertEquals(4, report.getAsJsonObject("scenario").get("normalizedOperations").getAsInt());
            final var reset = send(base + "/api/reset?summary=1", "POST", "{}", true, null);
            assertEquals(200, reset.statusCode(), reset.body());
            assertTrue(reset.body().contains("\"cursor\":0"), reset.body());
            assertFalse(reset.body().contains("\"lanes\""), reset.body());
        }
    }

    @Test
    void disabledProfilingAndUntrustedRequestsLeaveTheCursorAlone() throws Exception {
        try (final var server = new SimulatorServer(0, null)) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            assertEquals(403, send(base + "/api/profile", "POST", "{}", false, null).statusCode());
            assertEquals(403, send(base + "/api/profile", "POST", "{}", true, "https://untrusted.example").statusCode());
            assertEquals(405, send(base + "/api/profile", "GET", null, false, null).statusCode());
            assertEquals(503, send(base + "/api/profile", "POST", "{}", true, base).statusCode());
            final var state = send(base + "/api/state", "GET", null, false, null);
            assertTrue(state.body().contains("\"profileAvailable\":false"), state.body());
            assertTrue(state.body().contains("\"cursor\":0"), state.body());
        }
    }

    @Test
    void profilesLiveStepsAndStopsBeforeConvertingTheAttachment() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        final var bin = Files.createDirectory(temporary.resolve("bin"));
        final var asprof = bin.resolve("asprof");
        Files.writeString(asprof, """
                #!/bin/sh
                set -eu
                directory=$(dirname "$0")
                printf '%s\\n' "$1" >> "$directory/actions"
                case "$1" in
                  start)
                    case "$3" in
                      cpu|wall) test "$8 $9" = '-i 1ms' ;;
                      alloc) test "$8 $9" = '--alloc 64k' ;;
                    esac
                    printf 'fake JFR' > "$7"
                    ;;
                  stop) if [ -f "$directory/fail-stop" ]; then exit 1; fi ;;
                  *) exit 2 ;;
                esac
                """);
        final var converter = bin.resolve("jfrconv");
        Files.writeString(converter, """
                #!/bin/sh
                set -eu
                directory=$(dirname "$0")
                test "$2" = --include
                test "$3" = '\\Qone/cloud/benchmark/simulator/SimulatorSession.step\\E'
                test "$(cat "$4")" = 'fake JFR'
                case "$1:$5" in
                  --alloc:*.collapsed)
                    printf '%s\\n' samples >> "$directory/actions"
                    printf 'SimulatorSession.step;allocate 1\\n' > "$5"
                    ;;
                  --alloc:*.html)
                    printf '%s\\n' convert >> "$directory/actions"
                    printf '<html>allocation profile</html>' > "$5"
                    ;;
                  --cpu:*.collapsed|--wall:*.collapsed)
                    printf '%s\\n' samples >> "$directory/actions"
                    : > "$5"
                    ;;
                  *) exit 2 ;;
                esac
                """);
        assertTrue(asprof.toFile().setExecutable(true));
        assertTrue(converter.toFile().setExecutable(true));
        try (final var server = new SimulatorServer(0, new AsyncProfiler(temporary))) {
            server.start();
            final var base = "http://127.0.0.1:" + server.port();
            final var state = send(base + "/api/state", "GET", null, false, null);
            assertTrue(state.body().contains("\"profileAvailable\":true"), state.body());
            assertEquals(400, send(base + "/api/profile", "POST",
                    "{\"count\":10001}", true, base).statusCode());
            assertEquals(400, send(base + "/api/profile", "POST",
                    "{\"event\":\"invalid\"}", true, base).statusCode());
            final var html = send(base + "/api/profile", "POST",
                    "{\"count\":2,\"event\":\"alloc\",\"format\":\"html\"}", true, base);
            assertEquals(200, html.statusCode(), html.body());
            assertEquals("attachment; filename=\"placement-alloc.html\"",
                    html.headers().firstValue("Content-Disposition").orElseThrow());
            assertEquals("2", html.headers().firstValue("X-Placement-Cursor").orElseThrow());
            assertEquals("<html>allocation profile</html>", html.body());
            assertEquals(List.of("start", "stop", "samples", "convert"), Files.readAllLines(bin.resolve("actions")));

            final var empty = send(base + "/api/profile", "POST",
                    "{\"count\":1,\"event\":\"cpu\",\"format\":\"html\"}", true, base);
            assertEquals(422, empty.statusCode(), empty.body());
            assertTrue(empty.body().contains("Сэмплов для графика нет"), empty.body());
            assertTrue(empty.body().contains("увеличьте число событий за прогон"), empty.body());
            assertTrue(empty.body().contains("\"cursor\":3"), empty.body());
            assertTrue(empty.headers().firstValue("Content-Disposition").isEmpty());
            final var afterEmpty = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertTrue(afterEmpty.body().contains("\"cursor\":3"), afterEmpty.body());

            Files.createFile(bin.resolve("fail-stop"));
            final var failed = send(base + "/api/profile", "POST",
                    "{\"count\":1,\"format\":\"jfr\"}", true, base);
            assertEquals(500, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains("asprof exited"), failed.body());
            final var after = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertTrue(after.body().contains("\"cursor\":4"), after.body());
            assertEquals(List.of("start", "stop", "samples", "convert", "start", "stop", "samples", "start", "stop"),
                    Files.readAllLines(bin.resolve("actions")));

            Files.delete(bin.resolve("fail-stop"));
            final var exhausted = send(base + "/api/profile", "POST",
                    "{\"all\":true,\"event\":\"wall\",\"format\":\"html\"}", true, base);
            assertEquals(422, exhausted.statusCode(), exhausted.body());
            assertTrue(exhausted.body().contains("\"cursor\":30"), exhausted.body());
            assertTrue(exhausted.body().contains("26 из 26 оставшихся событий"), exhausted.body());
            assertTrue(exhausted.body().contains("откройте сценарий с большим числом событий"), exhausted.body());
            assertFalse(exhausted.body().contains("увеличьте"), exhausted.body());

            assertEquals(200, send(base + "/api/reset", "POST", "{}", true, base).statusCode());
            assertEquals(200, send(base + "/api/step", "POST", "{\"count\":1}", true, base).statusCode());
            final int actionsBefore = Files.readAllLines(bin.resolve("actions")).size();
            final var all = send(base + "/api/profile", "POST",
                    "{\"all\":true,\"event\":\"alloc\",\"format\":\"html\"}", true, base);
            assertEquals(200, all.statusCode(), all.body());
            assertEquals("30", all.headers().firstValue("X-Placement-Cursor").orElseThrow());
            assertEquals("<html>allocation profile</html>", all.body());
            final var actions = Files.readAllLines(bin.resolve("actions"));
            assertEquals(List.of("start", "stop", "samples", "convert"), actions.subList(actionsBefore, actions.size()));
        }
    }

    private JsonObject uploadExperimentDataset(String base) throws Exception {
        final var directory = temporary.resolve("experiment-dataset");
        writeTrace(directory, new Record[]{
                alloc(1, new int[]{1, 2}, 4, 4096), alloc(2, new int[]{1, 2}, 2, 1024),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2)}, 2, 3, -3, -2);
        final var snapshot = Files.readAllBytes(directory.resolve("snapshot.pb"));
        final var operations = Files.readAllBytes(directory.resolve("operations.pb"));
        final var request = HttpRequest.newBuilder(URI.create(base + "/api/experiments/datasets"))
                .header("X-Placement-UI", "1")
                .header("X-Snapshot-Length", Integer.toString(snapshot.length))
                .header("X-Dataset-Name", "tiny-dataset")
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(snapshot),
                        HttpRequest.BodyPublishers.ofByteArray(operations)))
                .build();
        final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        final var dataset = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("tiny-dataset", dataset.get("name").getAsString());
        assertFalse(dataset.get("id").getAsString().isBlank());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot)),
                dataset.getAsJsonObject("input").get("snapshotSha256").getAsString());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(operations)),
                dataset.getAsJsonObject("input").get("operationsSha256").getAsString());
        return dataset;
    }

    private static JsonObject experimentRequest(String datasetId) {
        final var request = JsonParser.parseString("""
                {"algorithms":[{"id":"fitcount","parameters":{"seed":7,"fitCap":3}}],
                 "forks":1,"warmups":0,"measurements":1,"timeoutSeconds":30,"gpuModel":"devices20",
                 "bulkSize":1,"maxOperations":0,"catalogueLimit":2,"probeCap":1,"inflationAttempts":0}
                """).getAsJsonObject();
        final var datasets = new JsonArray();
        datasets.add(datasetId);
        request.add("datasets", datasets);
        return request;
    }

    private static JsonObject profileRequest(JsonObject dataset, JsonObject cell, JsonObject options) {
        final var request = new JsonObject();
        request.addProperty("datasetId", dataset.get("id").getAsString());
        request.add("algorithm", cell.get("algorithm").deepCopy());
        request.add("input", cell.get("input").deepCopy());
        final var scenario = new JsonObject();
        for (final var key : List.of("digest", "gpuModel", "bulkSize")) {
            scenario.add(key, cell.getAsJsonObject("scenario").get(key).deepCopy());
        }
        request.add("scenario", scenario);
        final var selectedOptions = new JsonObject();
        for (final var key : List.of("maxOperations", "catalogueLimit", "probeCap")) {
            selectedOptions.add(key, options.get(key).deepCopy());
        }
        request.add("options", selectedOptions);
        request.addProperty("event", "cpu");
        request.addProperty("format", "jfr");
        request.addProperty("timeoutSeconds", 30);
        return request;
    }

    private JsonObject awaitExperimentJob(String base, String id) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            final var response = send(base + "/api/experiments/job?id=" + id, "GET", null, false, null);
            assertEquals(200, response.statusCode(), response.body());
            final var job = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!List.of("running", "cancelling").contains(job.get("status").getAsString())) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Experiment job timed out");
    }

    private static ProcessHandle awaitBenchmarkWorker() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final var worker = ProcessHandle.current().descendants().filter(process -> process.info().arguments()
                    .map(arguments -> Arrays.asList(arguments).contains("benchmark-worker")).orElse(false)).findFirst();
            if (worker.isPresent()) {
                return worker.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The benchmark did not start an isolated worker");
    }

    private static <T> T withCliClasspath(Callable<T> task) throws Exception {
        synchronized (SimulatorServerTest.class) {
            final var previous = System.getProperty("java.class.path");
            final var entries = new LinkedHashSet<String>();
            for (var loader = SimulatorServerTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
                if (loader instanceof URLClassLoader urls) {
                    for (final var url : urls.getURLs()) {
                        if (url.getProtocol().equals("file")) {
                            entries.add(Path.of(url.toURI()).toString());
                        }
                    }
                }
            }
            if (previous != null && !previous.isBlank()) {
                entries.add(previous);
            }
            System.setProperty("java.class.path", String.join(File.pathSeparator, entries));
            try {
                return task.call();
            } finally {
                if (previous == null) {
                    System.clearProperty("java.class.path");
                } else {
                    System.setProperty("java.class.path", previous);
                }
            }
        }
    }

    private HttpResponse<String> send(String url, String method, String body, boolean trusted, String origin)
            throws Exception {
        final var request = HttpRequest.newBuilder(URI.create(url));
        if (trusted) {
            request.header("X-Placement-UI", "1");
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
