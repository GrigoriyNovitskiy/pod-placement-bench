package one.cloud.benchmark.simulator;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.profiling.AsyncProfiler;
import one.cloud.benchmark.runner.BenchmarkExperiment;
import one.cloud.benchmark.runner.Scenario;

public final class SimulatorServer implements AutoCloseable {
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final long MAX_DATASET_BYTES = 128L * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private static final Map<String, String> ASSETS = Map.of(
            "/", "index.html", "/app.js", "app.js", "/views.js", "views.js", "/style.css", "style.css",
            "/benchmarks", "benchmarks.html", "/benchmarks.js", "benchmarks.js",
            "/benchmark-views.js", "benchmark-views.js", "/benchmarks.css", "benchmarks.css");
    private final HttpServer server;
    private final AsyncProfiler profiler;
    private final BenchmarkWorkspace experiments;
    private final ExecutorService owner = Executors.newSingleThreadExecutor();
    private final ExecutorService benchmarkWorker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("placement-benchmark").factory());
    private SimulatorSession session;
    private ManualScenario.Config config;
    private Scenario datasetScenario;
    private BenchmarkJob benchmarkJob;
    private long nextBenchmarkId;

    private record BenchmarkJob(String id, String source, BenchmarkExperiment.ScenarioInfo scenario,
            BenchmarkExperiment.Options options, Future<List<BenchmarkLane>> result) {
    }

    private record BenchmarkLane(AlgorithmRegistry.Settings algorithm, BenchmarkExperiment.Quality quality,
            String error) {
    }

    private record BenchmarkStatus(int formatVersion, String measurement, String id, String status, String source,
            BenchmarkExperiment.ScenarioInfo scenario, BenchmarkExperiment.Options options, List<BenchmarkLane> lanes,
            String error) {
    }

    public SimulatorServer(int port) throws IOException {
        this(port, AsyncProfiler.configured());
    }

    SimulatorServer(int port, AsyncProfiler profiler) throws IOException {
        this.profiler = profiler;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        experiments = new BenchmarkWorkspace(profiler);
        server.setExecutor(owner);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public static void main(String[] arguments) throws Exception {
        launch(arguments, System.out, System.err);
    }

    public static void launch(String[] arguments, PrintStream output, PrintStream errors) throws Exception {
        int port = 8765;
        boolean open = true;
        final var seen = new HashSet<String>();
        for (int index = 0; index < arguments.length; index++) {
            if (!seen.add(arguments[index])) {
                throw new IllegalArgumentException("Repeated option: " + arguments[index]);
            }
            switch (arguments[index]) {
                case "--port" -> {
                    if (++index == arguments.length) {
                        throw new IllegalArgumentException("--port requires a number");
                    }
                    port = Integer.parseInt(arguments[index]);
                }
                case "--no-open" -> open = false;
                default -> throw new IllegalArgumentException("Unknown option: " + arguments[index]);
            }
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        final var simulator = new SimulatorServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                simulator.close();
            } catch (Exception failure) {
                errors.println("Simulator shutdown failed: " + failure.getMessage());
            }
        }));
        simulator.start();
        final var url = URI.create("http://127.0.0.1:" + simulator.port());
        output.println("Placement simulator: " + url);
        if (open && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(url);
            } catch (IOException failure) {
                errors.println("Open this address in your browser: " + url);
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                validateHost(exchange);
                final String path = exchange.getRequestURI().getPath();
                if (ASSETS.containsKey(path)) {
                    requireMethod(exchange, "GET");
                    asset(exchange, ASSETS.get(path));
                    return;
                }
                if (path.equals("/api/experiments") || path.startsWith("/api/experiments/")) {
                    experiment(exchange, path);
                    return;
                }
                if (!path.startsWith("/api/")) {
                    throw new RequestFailure(404, "Not found");
                }
                final boolean mutation = switch (path) {
                    case "/api/manual", "/api/dataset", "/api/step", "/api/reset", "/api/probes", "/api/profile" -> true;
                    case "/api/algorithms", "/api/benchmark" -> exchange.getRequestMethod().equals("POST");
                    case "/api/state", "/api/inspect", "/api/scenario" -> false;
                    default -> throw new RequestFailure(404, "Unknown API endpoint");
                };
                requireMethod(exchange, mutation ? "POST" : "GET");
                if (mutation) {
                    validateMutation(exchange);
                }
                if (path.equals("/api/profile")) {
                    profile(exchange);
                    return;
                }
                final Object result = dispatch(exchange, path);
                json(exchange, path.equals("/api/benchmark") && mutation ? 202 : 200, result);
            } catch (RequestFailure failure) {
                json(exchange, failure.status, Map.of("error", failure.getMessage()));
            } catch (IllegalArgumentException | IllegalStateException | JsonParseException | ClassCastException
                    | UnsupportedOperationException failure) {
                json(exchange, 400, Map.of("error", message(failure)));
            } catch (Exception failure) {
                json(exchange, 500, Map.of("error", message(failure)));
            }
        }
    }

    private void experiment(HttpExchange exchange, String path) throws Exception {
        final boolean mutation = switch (path) {
            case "/api/experiments", "/api/experiments/job", "/api/experiments/artifact" -> false;
            case "/api/experiments/datasets", "/api/experiments/run", "/api/experiments/profile",
                    "/api/experiments/cancel" -> true;
            default -> throw new RequestFailure(404, "Unknown benchmark endpoint");
        };
        requireMethod(exchange, mutation ? "POST" : "GET");
        if (mutation) {
            validateMutation(exchange);
        }
        final var query = query(exchange.getRequestURI());
        if (path.equals("/api/experiments/artifact")) {
            experimentArtifact(exchange, query.get("id"));
            return;
        }
        try {
            final Object result = switch (path) {
                case "/api/experiments" -> experiments.state();
                case "/api/experiments/job" -> experiments.status(query.get("id"));
                case "/api/experiments/datasets" -> experimentDataset(exchange);
                case "/api/experiments/run" -> experiments.startBenchmark(body(exchange));
                case "/api/experiments/profile" -> experiments.startProfile(body(exchange));
                case "/api/experiments/cancel" -> {
                    final var request = body(exchange);
                    yield experiments.cancel(profileOption(request, "id", ""));
                }
                default -> throw new RequestFailure(404, "Unknown benchmark endpoint");
            };
            json(exchange, path.equals("/api/experiments/run") || path.equals("/api/experiments/profile")
                    ? 202 : 200, result);
        } catch (IllegalStateException busy) {
            throw new RequestFailure(409, message(busy));
        }
    }

    private JsonObject experimentDataset(HttpExchange exchange) throws Exception {
        final var directory = experiments.newDatasetDirectory();
        boolean registered = false;
        try {
            readDataset(exchange, directory);
            final var suppliedName = exchange.getRequestHeaders().getFirst("X-Dataset-Name");
            final var result = experiments.registerDataset(directory,
                    suppliedName == null ? "dataset" : URLDecoder.decode(suppliedName, StandardCharsets.UTF_8));
            registered = true;
            return result;
        } finally {
            if (!registered) {
                Files.deleteIfExists(directory.resolve("snapshot.pb"));
                Files.deleteIfExists(directory.resolve("operations.pb"));
                Files.deleteIfExists(directory);
            }
        }
    }

    private void experimentArtifact(HttpExchange exchange, String id) throws Exception {
        final var artifact = experiments.artifact(id);
        final var headers = exchange.getResponseHeaders();
        final boolean html = artifact.format().equals("html");
        headers.set("Content-Type", html ? "text/html; charset=utf-8" : "application/octet-stream");
        headers.set("Content-Disposition", (html ? "inline" : "attachment")
                + "; filename=\"" + artifact.filename() + "\"");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                + "style-src 'unsafe-inline'; img-src data:; sandbox allow-scripts allow-modals; frame-ancestors 'self'");
        exchange.sendResponseHeaders(200, Files.size(artifact.path()));
        Files.copy(artifact.path(), exchange.getResponseBody());
    }

    private Object dispatch(HttpExchange exchange, String path) throws Exception {
        final var query = query(exchange.getRequestURI());
        return switch (path) {
            case "/api/state" -> state(query);
            case "/api/inspect" -> current().inspect(integer(query, "lane", 0, 0, 5),
                    integer(query, "resource", 0, 1, Integer.MAX_VALUE));
            case "/api/scenario" -> {
                current();
                final var value = new JsonObject();
                value.add("config", JSON.toJsonTree(config));
                yield value;
            }
            case "/api/algorithms" -> {
                if (exchange.getRequestMethod().equals("GET")) {
                    yield Map.of("algorithms", AlgorithmRegistry.algorithms());
                }
                final var body = body(exchange);
                current().setAlgorithms(settings(body.getAsJsonArray("algorithms")));
                yield state(query);
            }
            case "/api/manual" -> manual(body(exchange), query);
            case "/api/dataset" -> dataset(exchange, query);
            case "/api/step" -> {
                final var body = body(exchange);
                if (allRemaining(body)) {
                    current().stepRemaining();
                } else {
                    final int count = integer(body, "count", 1, 1, 100);
                    current().step(count);
                }
                yield state(query);
            }
            case "/api/reset" -> {
                body(exchange);
                current().reset();
                yield state(query);
            }
            case "/api/probes" -> {
                body(exchange);
                yield current().probes();
            }
            case "/api/benchmark" -> exchange.getRequestMethod().equals("POST")
                    ? startBenchmark(body(exchange)) : benchmarkStatus(query.get("id"));
            default -> throw new RequestFailure(404, "Unknown API endpoint");
        };
    }

    private BenchmarkStatus startBenchmark(JsonObject body) throws Exception {
        if (benchmarkJob != null && !benchmarkJob.result.isDone()) {
            throw new RequestFailure(409, "A benchmark is already running");
        }
        final var keys = Set.of("scenarioDigest", "algorithms", "maxOperations", "catalogueLimit", "probeCap",
                "inflationAttempts", "inflationSeed", "bulkSize");
        for (final var key : body.keySet()) {
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("Unknown benchmark option: " + key);
            }
        }
        final var current = current();
        final var input = current.input();
        if (body.has("scenarioDigest")) {
            final var digest = body.get("scenarioDigest");
            if (!digest.isJsonPrimitive() || !digest.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("scenarioDigest must be a string");
            }
            if (!input.digest().equals(digest.getAsString())) {
                throw new RequestFailure(409, "The simulator scenario has changed");
            }
        }
        final int bulkSize = integer(body, "bulkSize", 1, 1, 100);
        final var scenario = (datasetScenario != null ? datasetScenario
                : Scenario.fromAtomicEvents(input.model(), input.requests(), input.events(), input.catalogue(), input.digest()))
                .withServiceBursts(bulkSize);
        final var options = new BenchmarkExperiment.Options(
                integer(body, "maxOperations", 0, 0, Integer.MAX_VALUE),
                integer(body, "catalogueLimit", 32, 0, Integer.MAX_VALUE),
                integer(body, "probeCap", 10, 1, 1000),
                integer(body, "inflationAttempts", 1000, 0, 10000),
                integer(body, "inflationSeed", 42, Integer.MIN_VALUE, Integer.MAX_VALUE));
        final var algorithms = body.has("algorithms") ? settings(body.getAsJsonArray("algorithms"))
                : current.settings();
        final var selected = algorithms.stream()
                .map(setting -> new BenchmarkExperiment.Algorithm(setting.id(), setting.parameters(), null)
                        .settings(null, bulkSize))
                .toList();
        final var result = benchmarkWorker.submit(() -> {
            final var lanes = new ArrayList<BenchmarkLane>();
            for (final var setting : selected) {
                try {
                    final var report = BenchmarkExperiment.quality(scenario, options, BenchmarkExperiment.factory(setting, null));
                    lanes.add(new BenchmarkLane(setting, report, null));
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception | LinkageError failure) {
                    lanes.add(new BenchmarkLane(setting, null, failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage()));
                }
            }
            return List.copyOf(lanes);
        });
        final var job = new BenchmarkJob(Long.toString(++nextBenchmarkId), input.source(),
                BenchmarkExperiment.ScenarioInfo.from(scenario), options, result);
        benchmarkJob = job;
        return benchmarkStatus(job, "running", List.of(), null);
    }

    private BenchmarkStatus benchmarkStatus(String id) throws Exception {
        final var job = benchmarkJob;
        if (id != null && (job == null || !id.equals(job.id))) {
            throw new RequestFailure(404, "Benchmark job not found");
        }
        if (job == null) {
            return benchmarkStatus(null, "idle", List.of(), null);
        }
        if (!job.result.isDone()) {
            return benchmarkStatus(job, "running", List.of(), null);
        }
        final List<BenchmarkLane> lanes;
        try {
            lanes = job.result.get();
        } catch (ExecutionException failure) {
            return benchmarkStatus(job, "failed", List.of(), message(failure.getCause()));
        }
        final long successful = lanes.stream().filter(lane -> lane.error == null).count();
        return benchmarkStatus(job, successful == lanes.size() ? "complete" : successful == 0 ? "failed" : "partial",
                lanes, null);
    }

    private static BenchmarkStatus benchmarkStatus(BenchmarkJob job, String status, List<BenchmarkLane> lanes,
            String error) {
        return new BenchmarkStatus(BenchmarkExperiment.FORMAT_VERSION, "quality-replay", job == null ? null : job.id,
                status, job == null ? null : job.source, job == null ? null : job.scenario,
                job == null ? null : job.options, lanes, error);
    }

    private SimulatorSession current() throws Exception {
        if (session == null) {
            final var initial = ManualScenario.example();
            session = new SimulatorSession(ManualScenario.compile(initial), defaults());
            config = initial;
        }
        return session;
    }

    private JsonObject state(Map<String, String> query) throws Exception {
        if ("1".equals(query.get("summary"))) {
            final var current = current();
            final var summary = new JsonObject();
            summary.addProperty("cursor", current.cursor());
            summary.addProperty("totalEvents", current.input().events().length);
            return summary;
        }
        final int hostOffset = integer(query, "hostOffset", 0, 0, Integer.MAX_VALUE);
        final int hostLimit = integer(query, "hostLimit", 48, 1, 48);
        final int eventOffset = integer(query, "eventOffset", 0, 0, Integer.MAX_VALUE);
        final int eventLimit = integer(query, "eventLimit", 50, 1, 200);
        final var result = current().state(hostOffset, hostLimit, eventOffset, eventLimit);
        result.addProperty("profileAvailable", profiler != null && profiler.supports(AsyncProfiler.Format.JFR));
        result.addProperty("flameGraphAvailable", profiler != null && profiler.supports(AsyncProfiler.Format.HTML));
        return result;
    }

    private void profile(HttpExchange exchange) throws Exception {
        final var request = body(exchange);
        final boolean all = allRemaining(request);
        final int count = integer(request, "count", 100, 1, SimulatorSession.MAX_STEP);
        final String eventName = profileOption(request, "event", "cpu");
        final String formatName = profileOption(request, "format", "html");
        final var event = AsyncProfiler.Event.parse(eventName);
        final var format = AsyncProfiler.Format.parse(formatName);
        if (profiler == null || !profiler.supports(format)) {
            throw new RequestFailure(503, "Профиль недоступен. Укажите ASYNC_PROFILER_HOME при запуске симулятора.");
        }
        final var current = current();
        final int startCursor = current.cursor();
        final int totalEvents = current.input().events().length;
        if (startCursor >= totalEvents) {
            throw new IllegalArgumentException("В потоке больше нет событий для профилирования.");
        }
        final String stepFrame = SimulatorSession.class.getName().replace('.', '/') + ".step";
        try (final var capture = profiler.capture(event, format, stepFrame, () -> {
            if (all) {
                current.stepRemaining();
            } else {
                current.step(count);
            }
            return current.cursor();
        })) {
            final var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/octet-stream");
            headers.set("Content-Disposition", "attachment; filename=\"placement-" + eventName + "." + formatName + "\"");
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Placement-Cursor", Integer.toString(capture.value()));
            exchange.sendResponseHeaders(200, Files.size(capture.file()));
            Files.copy(capture.file(), exchange.getResponseBody());
        } catch (AsyncProfiler.NoSamplesException failure) {
            final int cursor = current.cursor();
            final String hint = !all && totalEvents - cursor > count && count < SimulatorSession.MAX_STEP
                    ? "Для следующего профиля увеличьте число событий за прогон."
                    : "Для более подробного профиля откройте сценарий с большим числом событий.";
            json(exchange, 422, Map.of("cursor", cursor, "error",
                    "Сэмплов для графика нет. За прогон обработано %d из %d оставшихся событий. %s"
                            .formatted(cursor - startCursor, totalEvents - startCursor, hint)));
        }
    }

    private static boolean allRemaining(JsonObject request) {
        if (!request.has("all")) {
            return false;
        }
        final var value = request.get("all");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("all must be a boolean");
        }
        if (request.has("count")) {
            throw new IllegalArgumentException("Choose either all or count");
        }
        return value.getAsBoolean();
    }

    private static String profileOption(JsonObject request, String name, String fallback) {
        if (!request.has(name)) {
            return fallback;
        }
        final var value = request.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private JsonObject manual(JsonObject body, Map<String, String> query) throws Exception {
        if (!body.has("config") || !body.get("config").isJsonObject()) {
            throw new IllegalArgumentException("A manual scenario config is required");
        }
        final ManualScenario.Config nextConfig;
        try {
            nextConfig = JSON.fromJson(body.get("config"), ManualScenario.Config.class);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid manual scenario config: " + failure.getMessage(), failure);
        }
        final var input = ManualScenario.compile(nextConfig);
        final int cursor = integer(body, "cursor", 0, 0, input.events().length);
        final var selected = body.has("algorithms") ? settings(body.getAsJsonArray("algorithms"))
                : session == null ? defaults() : session.settings();
        replace(input, selected, cursor, nextConfig, null);
        return state(query);
    }

    private JsonObject dataset(HttpExchange exchange, Map<String, String> query) throws Exception {
        final Path directory = Files.createTempDirectory("placement-simulator-");
        final Path snapshot = directory.resolve("snapshot.pb");
        final Path operations = directory.resolve("operations.pb");
        try {
            readDataset(exchange, directory);
            final Scenario scenario;
            try {
                scenario = Scenario.from(Dataset.load(snapshot, operations));
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid dataset: " + failure.getMessage(), failure);
            }
            replace(ScenarioInput.from(scenario), session == null ? defaults() : session.settings(), 0, null, scenario);
            return state(query);
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(operations);
            Files.deleteIfExists(directory);
        }
    }

    private static void readDataset(HttpExchange exchange, Path directory) throws IOException {
        final long snapshotLength;
        try {
            snapshotLength = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Snapshot-Length"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("X-Snapshot-Length is required and must be a positive byte count");
        }
        if (snapshotLength <= 0 || snapshotLength >= MAX_DATASET_BYTES) {
            throw new IllegalArgumentException("Snapshot size is outside the upload limit");
        }
        checkLength(exchange, MAX_DATASET_BYTES);
        final Path snapshot = directory.resolve("snapshot.pb");
        final Path operations = directory.resolve("operations.pb");
        try (final var output = Files.newOutputStream(snapshot)) {
            copy(exchange.getRequestBody(), output, snapshotLength, true);
        }
        final long operationBytes;
        try (final var output = Files.newOutputStream(operations)) {
            operationBytes = copy(exchange.getRequestBody(), output, MAX_DATASET_BYTES - snapshotLength, false);
        }
        if (operationBytes == 0) {
            throw new IllegalArgumentException("Both snapshot.pb and operations.pb are required");
        }
    }

    private void replace(ScenarioInput input, List<AlgorithmRegistry.Settings> selected,
            int cursor, ManualScenario.Config nextConfig, Scenario nextDataset) throws Exception {
        final var next = new SimulatorSession(input, selected);
        try {
            for (int remaining = cursor; remaining > 0; remaining -= Math.min(remaining, 10000)) {
                next.step(Math.min(remaining, 10000));
            }
        } catch (Exception | Error failure) {
            next.close();
            throw failure;
        }
        final var previous = session;
        session = next;
        config = nextConfig;
        datasetScenario = nextDataset;
        if (previous != null) {
            previous.close();
        }
    }

    private static List<AlgorithmRegistry.Settings> defaults() {
        return List.of(AlgorithmRegistry.defaults("fitcount"), AlgorithmRegistry.defaults("geometric-best-fit"));
    }

    private static List<AlgorithmRegistry.Settings> settings(JsonArray values) {
        if (values == null || values.isEmpty() || values.size() > 6) {
            throw new IllegalArgumentException("Select between one and six algorithms");
        }
        final var result = new ArrayList<AlgorithmRegistry.Settings>();
        for (final var value : values) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Algorithm settings must be objects");
            }
            final var object = value.getAsJsonObject();
            for (final var key : object.keySet()) {
                if (!key.equals("id") && !key.equals("parameters")) {
                    throw new IllegalArgumentException("Unknown algorithm setting: " + key);
                }
            }
            final var id = object.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Algorithm id must be a string");
            }
            final var parameters = new LinkedHashMap<String, JsonPrimitive>();
            if (object.has("parameters")) {
                if (!object.get("parameters").isJsonObject()) {
                    throw new IllegalArgumentException("Algorithm parameters must be an object");
                }
                for (final var entry : object.getAsJsonObject("parameters").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) {
                        throw new IllegalArgumentException("Parameter " + entry.getKey() + " must be a primitive value");
                    }
                    parameters.put(entry.getKey(), entry.getValue().getAsJsonPrimitive());
                }
            }
            result.add(new AlgorithmRegistry.Settings(id.getAsString(), parameters));
        }
        return List.copyOf(result);
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        checkLength(exchange, MAX_JSON_BYTES);
        final byte[] bytes = exchange.getRequestBody().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new RequestFailure(413, "JSON body exceeds 4 MiB");
        }
        try {
            final var result = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            if (result == null) {
                throw new IllegalArgumentException("A JSON object is required");
            }
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid JSON object: " + failure.getMessage(), failure);
        }
    }

    private static Map<String, String> query(URI uri) {
        final var values = new HashMap<String, String>();
        if (uri.getRawQuery() == null) {
            return values;
        }
        for (final var part : uri.getRawQuery().split("&")) {
            final var pair = part.split("=", 2);
            final var name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            final var value = pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            if (values.put(name, value) != null) {
                throw new IllegalArgumentException("Repeated query parameter: " + name);
            }
        }
        return values;
    }

    private static int integer(Map<String, String> values, String name, int fallback, int min, int max) {
        try {
            final int value = values.containsKey(name) ? Integer.parseInt(values.get(name)) : fallback;
            if (value < min || value > max) {
                throw new NumberFormatException();
            }
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
        }
    }

    private static int integer(JsonObject values, String name, int fallback, int min, int max) {
        if (!values.has(name)) {
            return integer(Map.of(), name, fallback, min, max);
        }
        final var value = values.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return integer(Map.of(name, value.getAsString()), name, fallback, min, max);
    }

    private void validateHost(HttpExchange exchange) {
        final var host = exchange.getRequestHeaders().getFirst("Host");
        if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
            throw new RequestFailure(403, "Only local simulator requests are allowed");
        }
    }

    private void validateMutation(HttpExchange exchange) {
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Placement-UI"))) {
            throw new RequestFailure(403, "X-Placement-UI: 1 is required");
        }
        final var site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            throw new RequestFailure(403, "Cross-site mutations are forbidden");
        }
        final var origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://" + exchange.getRequestHeaders().getFirst("Host"))) {
            throw new RequestFailure(403, "Origin does not match the simulator");
        }
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!exchange.getRequestMethod().equals(expected)) {
            exchange.getResponseHeaders().set("Allow", expected);
            throw new RequestFailure(405, "Use " + expected + " for this endpoint");
        }
    }

    private static void checkLength(HttpExchange exchange, long maximum) {
        final var length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length == null) {
            return;
        }
        try {
            final long count = Long.parseLong(length);
            if (count < 0) {
                throw new NumberFormatException();
            }
            if (count > maximum) {
                throw new RequestFailure(413, "Upload exceeds " + maximum + " bytes");
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid Content-Length");
        }
    }

    private static long copy(InputStream input, OutputStream output, long maximum, boolean exact) throws IOException {
        final byte[] buffer = new byte[16384];
        long count = 0;
        while (count < maximum) {
            final int read = input.read(buffer, 0, (int) Math.min(buffer.length, maximum - count));
            if (read < 0) {
                if (exact) {
                    throw new IllegalArgumentException("Upload ended before the declared snapshot boundary");
                }
                return count;
            }
            output.write(buffer, 0, read);
            count += read;
        }
        if (!exact && input.read() != -1) {
            throw new RequestFailure(413, "Dataset exceeds 128 MiB");
        }
        return count;
    }

    private static void asset(HttpExchange exchange, String name) throws IOException {
        try (final var resource = SimulatorServer.class.getResourceAsStream("/one/cloud/benchmark/simulator/" + name)) {
            if (resource == null) {
                throw new RequestFailure(404, "UI asset is unavailable");
            }
            final var contentType = name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
            respond(exchange, 200, contentType, resource.readAllBytes());
        }
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        final var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() throws Exception {
        server.stop(0);
        experiments.close();
        benchmarkWorker.shutdownNow();
        try {
            owner.submit(() -> {
                if (session != null) {
                    try {
                        session.close();
                    } catch (Exception failure) {
                        throw new IllegalStateException("Unable to close algorithms", failure);
                    } finally {
                        session = null;
                    }
                }
            }).get();
        } finally {
            owner.shutdown();
            owner.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class RequestFailure extends IllegalArgumentException {
        private final int status;

        private RequestFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
