package one.cloud.benchmark.simulator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.cli.RunnerCli;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.profiling.AsyncProfiler;
import one.cloud.benchmark.runner.BenchmarkExperiment;
import one.cloud.benchmark.runner.Scenario;

final class BenchmarkWorkspace implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private static final Set<String> BENCHMARK_KEYS = Set.of("datasets", "algorithms", "seeds", "forks", "warmups",
            "measurements", "timeoutSeconds", "gpuModel", "bulkSize", "maxOperations", "catalogueLimit",
            "probeCap", "inflationAttempts", "inflationSeed");
    private static final int PROGRESS_BYTES = 8192;
    private static final int MAX_DATASETS = 32;
    private final Path root;
    private final AsyncProfiler profiler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("placement-experiments").factory());
    private final Set<Path> pendingDirectories = new LinkedHashSet<>();
    private final Map<String, UploadedDataset> datasets = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Job latest;
    private Job benchmark;
    private long nextDataset;
    private long nextJob;
    private boolean closed;

    record Artifact(Path path, String filename, String format) {
    }

    private record UploadedDataset(String id, Path path, JsonObject metadata) {
    }

    private static final class Job {
        private final String id;
        private final String kind;
        private final Path directory;
        private String status = "running";
        private String progress = "Preparing replay";
        private String error;
        private JsonObject report;
        private JsonObject profile;
        private Artifact artifact;
        private Process process;
        private final Set<ProcessHandle> terminatingDescendants = new LinkedHashSet<>();
        private boolean cancelled;

        private Job(String id, String kind, Path directory) {
            this.id = id;
            this.kind = kind;
            this.directory = directory;
        }
    }

    BenchmarkWorkspace(AsyncProfiler profiler) throws IOException {
        this.profiler = profiler;
        root = Files.createTempDirectory("placement-workspace-");
    }

    synchronized JsonObject state() {
        checkOpen();
        final var result = new JsonObject();
        final var uploaded = new JsonArray();
        datasets.values().forEach(dataset -> uploaded.add(dataset.metadata.deepCopy()));
        result.add("datasets", uploaded);
        result.add("job", latest == null ? null : jobJson(latest));
        result.add("benchmark", benchmark == null ? null : jobJson(benchmark));
        final var profiles = new JsonArray();
        jobs.values().stream().filter(job -> job.kind.equals("profile")).forEach(job -> profiles.add(jobJson(job)));
        result.add("profiles", profiles);
        result.addProperty("profileAvailable", supports(AsyncProfiler.Format.JFR));
        result.addProperty("flameGraphAvailable", supports(AsyncProfiler.Format.HTML));
        return result;
    }

    synchronized Path newDatasetDirectory() throws IOException {
        checkOpen();
        pendingDirectories.removeIf(directory -> !Files.exists(directory));
        if (datasets.size() + pendingDirectories.size() >= MAX_DATASETS) {
            throw new IllegalArgumentException("At most " + MAX_DATASETS + " datasets can be uploaded per workspace");
        }
        final var directory = Files.createTempDirectory(root, "dataset-");
        pendingDirectories.add(directory);
        return directory;
    }

    synchronized JsonObject registerDataset(Path directory, String name) throws IOException {
        checkOpen();
        if (!pendingDirectories.contains(directory)) {
            throw new IllegalArgumentException("Unknown upload directory");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Dataset name must contain between 1 and 120 characters");
        }
        final var input = new JsonObject();
        input.addProperty("snapshotSha256", sha256(directory.resolve("snapshot.pb")));
        input.addProperty("operationsSha256", sha256(directory.resolve("operations.pb")));
        final String id = Long.toString(++nextDataset);
        final var metadata = new JsonObject();
        metadata.addProperty("id", id);
        metadata.addProperty("name", name.strip());
        final String slug = name.strip().replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^-+|-+$", "");
        metadata.addProperty("directory", "./" + (slug.isBlank() || slug.equals(".") || slug.equals("..")
                ? "dataset-" + id : slug));
        metadata.add("input", input);
        datasets.put(id, new UploadedDataset(id, directory, metadata));
        pendingDirectories.remove(directory);
        return metadata.deepCopy();
    }

    synchronized JsonObject startBenchmark(JsonObject body) throws IOException {
        checkAvailable();
        keys(body, BENCHMARK_KEYS);
        final var requested = array(body, "datasets");
        if (requested.isEmpty()) throw new IllegalArgumentException("Choose at least one uploaded dataset");
        final var paths = new JsonArray();
        final var selected = new LinkedHashSet<String>();
        for (final var value : requested) {
            final var dataset = dataset(string(value, "dataset id"));
            if (!selected.add(dataset.id)) throw new IllegalArgumentException("Duplicate dataset: " + dataset.id);
            paths.add(dataset.path.toString());
        }
        final var manifest = new JsonObject();
        manifest.add("datasets", paths);
        final int bulkSize = integer(body, "bulkSize", 1, 1, 100);
        final var algorithms = array(body, "algorithms");
        if (algorithms.isEmpty()) throw new IllegalArgumentException("Choose at least one algorithm");
        final var normalized = new JsonArray();
        for (final var algorithm : algorithms) {
            normalized.add(JSON.toJsonTree(algorithm(object(algorithm, "algorithm"), bulkSize)));
        }
        manifest.add("algorithms", normalized);
        final String gpuModel = gpuModel(body);
        manifest.addProperty("gpuModel", gpuModel);
        manifest.addProperty("bulkSize", bulkSize);
        manifest.addProperty("forks", integer(body, "forks", 2, 1, 20));
        manifest.addProperty("warmups", integer(body, "warmups", 1, 0, 100));
        manifest.addProperty("measurements", integer(body, "measurements", 3, 1, 100));
        manifest.addProperty("timeoutSeconds", integer(body, "timeoutSeconds", 3600, 1, 86400));
        final var options = options(body);
        manifest.addProperty("maxOperations", options.maxOperations());
        manifest.addProperty("catalogueLimit", options.catalogueLimit());
        manifest.addProperty("probeCap", options.probeCap());
        manifest.addProperty("inflationAttempts", options.inflationAttempts());
        manifest.addProperty("inflationSeed", options.inflationSeed());
        if (body.has("seeds") && !body.get("seeds").isJsonNull()) {
            final var seeds = array(body, "seeds");
            if (seeds.isEmpty()) throw new IllegalArgumentException("seeds must be nonempty");
            for (final var value : seeds) {
                final long seed = integral(value, "seed");
                for (final var setting : normalized) {
                    final var parsed = JSON.fromJson(setting, AlgorithmRegistry.Settings.class);
                    new BenchmarkExperiment.Algorithm(parsed.id(), parsed.parameters(), null).settings(seed, bulkSize);
                }
            }
            manifest.add("seeds", seeds.deepCopy());
        }
        final var job = newJob("benchmark");
        benchmark = job;
        worker.submit(() -> runBenchmark(job, manifest));
        return jobJson(job);
    }

    synchronized JsonObject startProfile(JsonObject body) throws IOException {
        checkAvailable();
        keys(body, Set.of("datasetId", "algorithm", "input", "scenario", "options", "event", "format",
                "timeoutSeconds"));
        final var dataset = dataset(requiredString(body, "datasetId"));
        final var input = object(body.get("input"), "input");
        if (!dataset.metadata.getAsJsonObject("input").equals(input)) {
            throw new IllegalArgumentException("Upload the exact snapshot and operations files for this case");
        }
        final var scenario = object(body.get("scenario"), "scenario");
        final String digest = requiredString(scenario, "digest");
        final String gpuModel = gpuModel(scenario);
        final int bulkSize = integer(scenario, "bulkSize", 1, 1, 100);
        final var setting = algorithm(object(body.get("algorithm"), "algorithm"), bulkSize);
        final var requestedOptions = object(body.get("options"), "options");
        keys(requestedOptions, Set.of("maxOperations", "catalogueLimit", "probeCap", "inflationAttempts",
                "inflationSeed"));
        final var options = options(requestedOptions);
        final String event = optionalString(body, "event", "cpu");
        AsyncProfiler.Event.parse(event);
        final String format = optionalString(body, "format", "html");
        if (!supports(AsyncProfiler.Format.parse(format))) {
            throw new IllegalArgumentException("Configure ASYNC_PROFILER_HOME with support for " + format + " profiles");
        }
        final int timeout = integer(body, "timeoutSeconds", 3600, 1, 86400);
        final var job = newJob("profile");
        job.profile = body.deepCopy();
        job.profile.add("algorithm", JSON.toJsonTree(setting));
        job.profile.addProperty("event", event);
        job.profile.addProperty("format", format);
        worker.submit(() -> runProfile(job, dataset, setting, digest, gpuModel, bulkSize, options, event, format, timeout));
        return jobJson(job);
    }

    synchronized JsonObject status(String id) {
        checkOpen();
        return jobJson(job(id));
    }

    synchronized JsonObject cancel(String id) {
        checkOpen();
        final var job = job(id);
        if (active(job)) {
            job.cancelled = true;
            job.status = "cancelling";
            job.progress = "Cancelling replay";
            if (job.process != null) job.terminatingDescendants.addAll(terminate(job.process));
        }
        return jobJson(job);
    }

    synchronized Artifact artifact(String id) {
        checkOpen();
        final var job = job(id);
        if (!job.status.equals("complete") || job.artifact == null || !Files.isRegularFile(job.artifact.path)) {
            throw new IllegalArgumentException("Profile artifact is not available");
        }
        return job.artifact;
    }

    private void runBenchmark(Job job, JsonObject manifest) {
        try {
            final var config = job.directory.resolve("manifest.json");
            Files.writeString(config, JSON.toJson(manifest));
            final int exit = execute(job, List.of("benchmark", "--config", config.toString()), 0);
            if (cancelled(job)) return;
            final JsonObject report;
            try (final var reader = Files.newBufferedReader(job.directory.resolve("stdout.json"))) {
                report = object(JsonParser.parseReader(reader), "CLI report");
            }
            if (!report.has("cases") || !report.get("cases").isJsonArray()
                    || !report.has("measurement") || !report.get("measurement").getAsString().equals("isolated-jvm-full-replay")) {
                throw new IOException("Benchmark runner returned an invalid report");
            }
            synchronized (this) {
                if (job.cancelled) return;
                job.report = report;
                final long successful = report.getAsJsonArray("cases").asList().stream().filter(value -> {
                    final var item = value.getAsJsonObject();
                    return (!item.has("qualityError") || item.get("qualityError").isJsonNull())
                            && item.getAsJsonArray("forks").asList().stream().allMatch(fork -> {
                                final var error = fork.getAsJsonObject().get("error");
                                return error == null || error.isJsonNull();
                            });
                }).count();
                job.status = exit == 0 ? "complete" : successful > 0 ? "partial" : "failed";
                if (exit != 0) job.error = "One or more benchmark cases failed; see the report diagnostics";
            }
        } catch (Exception failure) {
            fail(job, failure);
        } finally {
            finish(job);
        }
    }

    private void runProfile(Job job, UploadedDataset dataset, AlgorithmRegistry.Settings setting, String digest,
            String gpuModel, int bulkSize, BenchmarkExperiment.Options options, String event, String format, int timeout) {
        try {
            verifyScenario(dataset, digest, gpuModel, bulkSize);
            if (cancelled(job)) return;
            final var output = job.directory.resolve("profile." + format);
            final var arguments = new ArrayList<>(List.of("run", "--dataset", dataset.path.toString(),
                    "--algorithm", setting.id(), "--gpu-model", gpuModel, "--bulk-size", Integer.toString(bulkSize),
                    "--probe-cap", Integer.toString(options.probeCap()), "--profile", output.toString(),
                    "--profile-event", event));
            for (final var parameter : setting.parameters().entrySet()) {
                arguments.add("--param");
                arguments.add(parameter.getKey() + "=" + parameter.getValue().getAsString());
            }
            if (options.maxOperations() > 0) arguments.addAll(List.of("--max-operations", "" + options.maxOperations()));
            if (options.catalogueLimit() > 0) arguments.addAll(List.of("--catalogue-limit", "" + options.catalogueLimit()));
            final int exit = execute(job, arguments, timeout);
            if (cancelled(job)) return;
            if (exit != 0 || !Files.isRegularFile(output)) {
                throw new IOException("Profile runner failed (exit " + exit + ")");
            }
            synchronized (this) {
                if (!job.cancelled) {
                    job.artifact = new Artifact(output, "placement-profile-" + job.id + "." + format, format);
                    job.status = "complete";
                }
            }
        } catch (Exception failure) {
            fail(job, failure);
        } finally {
            finish(job);
        }
    }

    private static void verifyScenario(UploadedDataset dataset, String digest, String gpuModel, int bulkSize)
            throws IOException {
        final var scenario = Scenario.from(Dataset.load(dataset.path.resolve("snapshot.pb"),
                dataset.path.resolve("operations.pb")), gpuModel.equals("aggregate")
                        ? ClusterSpec.GpuMode.AGGREGATE : ClusterSpec.GpuMode.DEVICES_20)
                .withServiceBursts(bulkSize);
        if (!scenario.digest.equals(digest)) {
            throw new IllegalArgumentException("Uploaded dataset does not match the selected case scenario");
        }
    }

    private int execute(Job job, List<String> arguments, int timeout) throws IOException, InterruptedException {
        final var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=" + job.directory,
                "-cp", System.getProperty("java.class.path"),
                RunnerCli.class.getName()));
        command.addAll(arguments);
        final Process process;
        synchronized (this) {
            if (job.cancelled || closed) throw new InterruptedException("Replay cancelled");
            process = new ProcessBuilder(command).redirectOutput(job.directory.resolve("stdout.json").toFile())
                    .redirectError(job.directory.resolve("stderr.log").toFile()).start();
            job.process = process;
            job.progress = "Runner started";
        }
        try {
            if (timeout > 0 && !process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw new IOException("Profile runner timed out after " + timeout + " seconds");
            }
            return process.waitFor();
        } finally {
            final List<ProcessHandle> descendants;
            synchronized (this) {
                job.terminatingDescendants.addAll(terminate(process));
                descendants = List.copyOf(job.terminatingDescendants);
            }
            try {
                reap(process, descendants);
            } finally {
                synchronized (this) {
                    job.process = null;
                    job.terminatingDescendants.clear();
                }
            }
        }
    }

    private synchronized boolean cancelled(Job job) {
        return job.cancelled || closed;
    }

    private synchronized void fail(Job job, Exception failure) {
        if (!job.cancelled && !closed) {
            job.status = "failed";
            job.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            final String diagnostics = tail(job.directory.resolve("stderr.log"), "");
            if (!diagnostics.isEmpty()) job.error += "\n" + diagnostics;
        }
    }

    private void finish(Job job) {
        synchronized (this) {
            if (job.cancelled || closed) job.status = "cancelled";
            job.progress = tail(job.directory.resolve("stderr.log"), job.progress);
        }
        if (closed) {
            try {
                deleteTree(root);
            } catch (IOException ignored) {
                // close() also attempts cleanup after the worker has stopped.
            }
        }
    }

    private Job newJob(String kind) throws IOException {
        final String id = Long.toString(++nextJob);
        final var job = new Job(id, kind, Files.createTempDirectory(root, "job-"));
        jobs.put(id, job);
        latest = job;
        return job;
    }

    private JsonObject jobJson(Job job) {
        final var result = new JsonObject();
        result.addProperty("id", job.id);
        result.addProperty("kind", job.kind);
        result.addProperty("status", job.status);
        result.addProperty("progress", active(job) ? tail(job.directory.resolve("stderr.log"), job.progress) : job.progress);
        result.addProperty("error", job.error);
        result.add("report", job.report == null ? null : job.report.deepCopy());
        if (job.profile != null) result.add("profile", job.profile.deepCopy());
        return result;
    }

    private UploadedDataset dataset(String id) {
        final var dataset = datasets.get(id);
        if (dataset == null) throw new IllegalArgumentException("Uploaded dataset not found: " + id);
        return dataset;
    }

    private Job job(String id) {
        final var job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Experiment job not found: " + id);
        return job;
    }

    private static boolean active(Job job) {
        return job != null && (job.status.equals("running") || job.status.equals("cancelling"));
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Benchmark workspace is closed");
    }

    private void checkAvailable() {
        checkOpen();
        if (active(latest)) throw new IllegalStateException("An experiment is already running");
    }

    private boolean supports(AsyncProfiler.Format format) {
        return profiler != null && profiler.supports(format);
    }

    private static AlgorithmRegistry.Settings algorithm(JsonObject body, int bulkSize) {
        keys(body, Set.of("id", "parameters"));
        final String id = requiredString(body, "id");
        final var parameters = new LinkedHashMap<String, JsonPrimitive>();
        if (body.has("parameters") && !body.get("parameters").isJsonNull()) {
            for (final var entry : object(body.get("parameters"), "parameters").entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("Invalid algorithm parameter");
                parameters.put(entry.getKey(), entry.getValue().getAsJsonPrimitive());
            }
        }
        final var descriptor = AlgorithmRegistry.algorithms().stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown algorithm: " + id));
        if (descriptor.requiresPrior()) throw new IllegalArgumentException(id + " requires a prior file; use the CLI");
        final var setting = new BenchmarkExperiment.Algorithm(id, parameters, null).settings(null, bulkSize);
        if (setting.parameters().containsKey("model") && !setting.parameters().get("model").getAsString().isEmpty()) {
            throw new IllegalArgumentException("Only built-in models are available in the benchmark workspace");
        }
        return setting;
    }

    private static BenchmarkExperiment.Options options(JsonObject body) {
        return new BenchmarkExperiment.Options(integer(body, "maxOperations", 0, 0, Integer.MAX_VALUE),
                integer(body, "catalogueLimit", 0, 0, Integer.MAX_VALUE), integer(body, "probeCap", 10, 1, 1000),
                integer(body, "inflationAttempts", 1000, 0, 10000),
                body.has("inflationSeed") && !body.get("inflationSeed").isJsonNull()
                        ? integral(body.get("inflationSeed"), "inflationSeed") : 42);
    }

    private static String gpuModel(JsonObject body) {
        final String value = optionalString(body, "gpuModel", "devices20");
        if (!value.equals("devices20") && !value.equals("aggregate")) {
            throw new IllegalArgumentException("gpuModel must be devices20 or aggregate");
        }
        return value;
    }

    private static void keys(JsonObject body, Set<String> allowed) {
        if (body == null) throw new IllegalArgumentException("A JSON object is required");
        for (final var key : body.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown option: " + key);
        }
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject body, String name) {
        final var value = body.get(name);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(name + " must be an array");
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject body, String name) {
        return string(body.get(name), name);
    }

    private static String optionalString(JsonObject body, String name, String fallback) {
        return !body.has(name) || body.get(name).isJsonNull() ? fallback : requiredString(body, name);
    }

    private static String string(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) throw new IllegalArgumentException(name + " must be a nonempty string");
        return value.getAsString();
    }

    private static int integer(JsonObject body, String name, int fallback, int minimum, int maximum) {
        final long value = !body.has(name) || body.get(name).isJsonNull() ? fallback : integral(body.get(name), name);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return (int) value;
    }

    private static long integral(JsonElement value, String name) {
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        try (final var stream = Files.newInputStream(path)) {
            final var buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String tail(Path path, String fallback) {
        if (!Files.isRegularFile(path)) return fallback;
        try (final var file = new RandomAccessFile(path.toFile(), "r")) {
            final long size = file.length();
            file.seek(Math.max(0, size - PROGRESS_BYTES));
            final var bytes = new byte[(int) Math.min(size, PROGRESS_BYTES)];
            file.readFully(bytes);
            final String value = new String(bytes, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? fallback : value;
        } catch (IOException failure) {
            return fallback;
        }
    }

    private static List<ProcessHandle> terminate(Process process) {
        final var descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        return descendants;
    }

    private static void reap(Process process, List<ProcessHandle> descendants) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    process.waitFor();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            for (final var descendant : descendants) {
                while (descendant.isAlive()) {
                    try {
                        descendant.onExit().get();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    } catch (ExecutionException failure) {
                        throw new IOException("Failed to await replay worker termination", failure.getCause());
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (final var paths = Files.walk(directory)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (final var job : jobs.values()) {
                if (active(job)) {
                    job.cancelled = true;
                    job.status = "cancelling";
                    if (job.process != null) job.terminatingDescendants.addAll(terminate(job.process));
                }
            }
        }
        worker.shutdownNow();
        try {
            if (worker.awaitTermination(10, TimeUnit.SECONDS)) deleteTree(root);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing benchmark workspace", failure);
        }
    }
}
