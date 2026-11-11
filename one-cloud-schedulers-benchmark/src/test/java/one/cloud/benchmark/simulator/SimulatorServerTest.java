package one.cloud.benchmark.simulator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertEquals(403, send(base + "/api/reset", "POST", "{}", true,
                    "https://untrusted.example").statusCode());
            assertEquals(404, send(base + "/AGENTS.md", "GET", null, false, null).statusCode());
            assertEquals(404, send(base + "/../AGENTS.md", "GET", null, false, null).statusCode());
            assertEquals(405, send(base + "/api/reset", "GET", null, false, null).statusCode());
        }
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
            final var drained = send(base + "/api/step", "POST", "{\"count\":4}", true, null);
            assertEquals(200, drained.statusCode(), drained.body());
            assertTrue(drained.body().contains("\"cursor\":4"), drained.body());
            final var scenario = send(base + "/api/scenario", "GET", null, false, null);
            assertEquals("{\"config\":null}", scenario.body());
            final var summary = send(base + "/api/state?summary=1", "GET", null, false, null);
            assertEquals(200, summary.statusCode(), summary.body());
            assertTrue(summary.body().contains("\"cursor\":4"), summary.body());
            assertFalse(summary.body().contains("\"lanes\""), summary.body());
            final var reset = send(base + "/api/reset?summary=1", "POST", "{}", true, null);
            assertEquals(200, reset.statusCode(), reset.body());
            assertTrue(reset.body().contains("\"cursor\":0"), reset.body());
            assertFalse(reset.body().contains("\"lanes\""), reset.body());
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
