package one.cloud.benchmark.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.runner.BenchmarkExperiment;
import one.cloud.benchmark.runner.BenchmarkExperiment.Algorithm;
import one.cloud.benchmark.runner.BenchmarkExperiment.Measurement;
import one.cloud.benchmark.runner.BenchmarkExperiment.Options;
import one.cloud.benchmark.runner.BenchmarkExperiment.Quality;
import one.cloud.benchmark.runner.BenchmarkExperiment.ScenarioInfo;
import one.cloud.benchmark.runner.Scenario;

final class BenchmarkCli {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private static final String USAGE = """
            Usage: placement-bench benchmark --config FILE
            Run a JSON experiment matrix. Results go to stdout, progress to stderr.
            See one-cloud-schedulers-benchmark/docs/runner/README.md for the manifest and metrics.
            """;
    private static final Set<String> CONFIG_KEYS = Set.of("datasets", "algorithms", "seeds", "forks", "warmups",
            "measurements", "timeoutSeconds", "gpuModel", "bulkSize", "maxOperations", "catalogueLimit",
            "probeCap", "inflationAttempts", "inflationSeed");
    private static final Set<String> ALGORITHM_KEYS = Set.of("id", "parameters", "rolloutPrior");

    private BenchmarkCli() {
    }

    record Config(List<String> datasets, List<Algorithm> algorithms, List<Long> seeds, Integer forks, Integer warmups,
            Integer measurements, Integer timeoutSeconds, String gpuModel, Integer bulkSize, Integer maxOperations,
            Integer catalogueLimit, Integer probeCap, Integer inflationAttempts, Long inflationSeed) {
    }

    private record Plan(List<String> datasets, List<Selection> algorithms, int forks, int warmups, int measurements,
            int timeoutSeconds, ClusterSpec.GpuMode gpuMode, int bulkSize, Options options) {
    }

    private record Selection(AlgorithmRegistry.Settings settings, String prior) {
    }

    private record Work(String dataset, Selection selection, ClusterSpec.GpuMode gpuMode, int bulkSize,
            Options options, int warmups, int measurements, boolean quality) {
    }

    private record Input(String snapshotSha256, String operationsSha256) {
    }

    private record Worker(Input input, ScenarioInfo scenario, String priorSha256, long maxHeapBytes,
            List<Measurement> measurements, Quality quality) {
    }

    private record Fork(int fork, Long maxHeapBytes, List<Measurement> measurements, String error) {
    }

    private record Distribution(int samples, Double min, Double median, Double max) {
    }

    private record Summary(Distribution replayNanos, Distribution eventsPerSecond, Distribution scheduleP50Nanos,
            Distribution scheduleP95Nanos, Distribution scheduleP99Nanos, Distribution removalP99Nanos) {
    }

    private record Cell(String dataset, AlgorithmRegistry.Settings algorithm, String rolloutPrior, String priorSha256,
            Input input, ScenarioInfo scenario, List<Fork> forks, Summary timing, Quality quality, String qualityError) {
        boolean successful() {
            return qualityError == null && forks.stream().allMatch(fork -> fork.error == null);
        }
    }

    private record Report(int formatVersion, String measurement, String java, String vm, String os, String arch,
            int availableProcessors, int warmupsPerFork, int measurementsPerFork, int forks, int timeoutSeconds,
            Options options, List<Cell> cases) {
    }

    static int run(String[] arguments, PrintStream output, PrintStream errors) {
        if (arguments.length == 2 && arguments[1].equals("--help")) {
            output.print(USAGE);
            return 0;
        }
        if (arguments.length != 3 || !arguments[1].equals("--config")) {
            errors.print(USAGE);
            return 2;
        }
        final Plan plan;
        try {
            plan = plan(Path.of(arguments[2]).toAbsolutePath().normalize());
        } catch (IOException | RuntimeException failure) {
            errors.println("Invalid benchmark config: " + message(failure));
            return 2;
        }

        try {
            final var temp = Files.createTempDirectory("placement-benchmark-");
            final var cases = new ArrayList<Cell>();
            final var inputs = new HashMap<String, Input>();
            try {
                for (final var dataset : plan.datasets) {
                    for (final var selection : plan.algorithms) {
                        final int current = cases.size() + 1;
                        errors.printf("Benchmark %d/%d: %s %s%n", current, plan.datasets.size() * plan.algorithms.size(),
                                Path.of(dataset).getFileName(), selection.settings);
                        cases.add(runCase(temp, plan, dataset, selection, inputs, errors));
                    }
                }
            } finally {
                Files.deleteIfExists(temp.resolve("work.json"));
                Files.deleteIfExists(temp.resolve("result.json"));
                Files.deleteIfExists(temp.resolve("worker.log"));
                Files.deleteIfExists(temp);
            }
            output.println(JSON.toJson(new Report(BenchmarkExperiment.FORMAT_VERSION, "isolated-jvm-full-replay",
                    System.getProperty("java.runtime.version"), System.getProperty("java.vm.name"),
                    System.getProperty("os.name"), System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(), plan.warmups, plan.measurements, plan.forks,
                    plan.timeoutSeconds, plan.options, cases)));
            return cases.stream().allMatch(Cell::successful) ? 0 : 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            errors.println("Benchmark interrupted");
            return 1;
        } catch (IOException failure) {
            errors.println("Benchmark failed: " + message(failure));
            return 1;
        }
    }

    private static Cell runCase(Path temp, Plan plan, String dataset, Selection selection, Map<String, Input> inputs,
            PrintStream errors) throws InterruptedException {
        final var forks = new ArrayList<Fork>();
        final var samples = new ArrayList<Measurement>();
        ScenarioInfo scenario = null;
        String priorHash = null;
        for (int fork = 1; fork <= plan.forks; fork++) {
            try {
                final var worker = fork(temp, new Work(dataset, selection, plan.gpuMode, plan.bulkSize, plan.options,
                        plan.warmups, plan.measurements, false), plan.timeoutSeconds);
                verifyInput(inputs, dataset, worker, scenario, priorHash);
                scenario = worker.scenario;
                priorHash = worker.priorSha256;
                forks.add(new Fork(fork, worker.maxHeapBytes, worker.measurements, null));
                samples.addAll(worker.measurements);
            } catch (IOException | RuntimeException failure) {
                final var error = message(failure);
                forks.add(new Fork(fork, null, List.of(), error));
                errors.printf("  Fork %d failed: %s%n", fork, error);
            }
        }
        Quality quality = null;
        String qualityError = null;
        if (scenario != null) {
            try {
                final var worker = fork(temp, new Work(dataset, selection, plan.gpuMode, plan.bulkSize, plan.options,
                        0, 0, true), plan.timeoutSeconds);
                verifyInput(inputs, dataset, worker, scenario, priorHash);
                quality = worker.quality;
            } catch (IOException | RuntimeException failure) {
                qualityError = message(failure);
                errors.println("  Quality replay failed: " + qualityError);
            }
        } else {
            qualityError = "Skipped: all measured forks failed";
        }
        return new Cell(dataset, selection.settings, selection.prior, priorHash,
                scenario == null ? null : inputs.get(dataset), scenario, forks,
                summary(samples), quality, qualityError);
    }

    private static void verifyInput(Map<String, Input> inputs, String dataset, Worker worker, ScenarioInfo scenario,
            String priorHash) throws IOException {
        final var previous = inputs.putIfAbsent(dataset, worker.input);
        if (previous != null && !previous.equals(worker.input)
                || scenario != null && (!scenario.equals(worker.scenario)
                        || !java.util.Objects.equals(priorHash, worker.priorSha256))) {
            throw new IOException("Input changed between benchmark runs: " + dataset);
        }
    }

    private static Worker fork(Path directory, Work work, int timeoutSeconds) throws IOException, InterruptedException {
        final var request = directory.resolve("work.json");
        final var response = directory.resolve("result.json");
        final var log = directory.resolve("worker.log");
        Files.writeString(request, JSON.toJson(work));
        Files.deleteIfExists(response);
        final var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("java.class.path"), RunnerCli.class.getName(), "benchmark-worker",
                request.toString(), response.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Worker timed out after " + timeoutSeconds + " s");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(response)) {
                throw new IOException("Worker exit " + process.exitValue() + ": " + tail(log));
            }
            try (final var reader = Files.newBufferedReader(response)) {
                final var worker = JSON.fromJson(reader, Worker.class);
                if (worker == null || worker.input == null || worker.scenario == null || worker.measurements == null
                        || worker.measurements.size() != work.measurements || work.quality != (worker.quality != null)) {
                    throw new IOException("Invalid benchmark worker result");
                }
                return worker;
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    static int worker(String[] arguments, PrintStream errors) {
        try {
            final Work work;
            try (final var reader = Files.newBufferedReader(Path.of(arguments[1]))) {
                work = JSON.fromJson(reader, Work.class);
            }
            final var directory = Path.of(work.dataset);
            final var snapshot = directory.resolve("snapshot.pb");
            final var operations = directory.resolve("operations.pb");
            final var input = new Input(RunnerCli.sha256(snapshot), RunnerCli.sha256(operations));
            final var scenario = Scenario.from(Dataset.load(snapshot, operations), work.gpuMode)
                    .withServiceBursts(work.bulkSize);
            final Path prior = work.selection.prior == null ? null : Path.of(work.selection.prior);
            final var priorHash = prior == null ? null : RunnerCli.sha256(prior);
            final var factory = BenchmarkExperiment.factory(work.selection.settings, prior);
            for (int warmup = 0; warmup < work.warmups; warmup++) {
                BenchmarkExperiment.measure(scenario, work.options, factory);
            }
            final var measured = new ArrayList<Measurement>();
            for (int iteration = 0; iteration < work.measurements; iteration++) {
                measured.add(BenchmarkExperiment.measure(scenario, work.options, factory));
            }
            final var quality = work.quality ? BenchmarkExperiment.quality(scenario, work.options, factory) : null;
            final var result = new Worker(input, ScenarioInfo.from(scenario), priorHash,
                    Runtime.getRuntime().maxMemory(), measured, quality);
            try (final var writer = Files.newBufferedWriter(Path.of(arguments[2]))) {
                JSON.toJson(result, writer);
            }
            return 0;
        } catch (Exception | LinkageError failure) {
            errors.println(message(failure));
            return 1;
        }
    }

    private static Plan plan(Path path) throws IOException {
        final Config config;
        try (final var reader = Files.newBufferedReader(path)) {
            final var input = JSON.fromJson(reader, JsonObject.class);
            if (input == null) {
                throw new IllegalArgumentException("Expected a JSON object");
            }
            keys(input, CONFIG_KEYS);
            if (input.has("algorithms") && input.get("algorithms").isJsonArray()) {
                for (final var algorithm : input.getAsJsonArray("algorithms")) {
                    if (!algorithm.isJsonObject()) {
                        throw new IllegalArgumentException("Each algorithm must be an object");
                    }
                    keys(algorithm.getAsJsonObject(), ALGORITHM_KEYS);
                }
            }
            config = JSON.fromJson(input, Config.class);
        }
        if (config.datasets == null || config.datasets.isEmpty() || config.algorithms == null || config.algorithms.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one dataset and algorithm");
        }
        final var datasets = new LinkedHashSet<String>();
        for (final var dataset : config.datasets) {
            if (dataset == null || dataset.isBlank()) {
                throw new IllegalArgumentException("Dataset paths cannot be empty");
            }
            if (!datasets.add(path.getParent().resolve(dataset).normalize().toString())) {
                throw new IllegalArgumentException("Duplicate dataset: " + dataset);
            }
        }
        final int bulkSize = bound(config.bulkSize, 1, 1, 100, "bulkSize");
        final var gpuMode = switch (config.gpuModel == null ? "devices20" : config.gpuModel) {
            case "devices20" -> ClusterSpec.GpuMode.DEVICES_20;
            case "aggregate" -> ClusterSpec.GpuMode.AGGREGATE;
            default -> throw new IllegalArgumentException("gpuModel must be devices20 or aggregate");
        };
        if (config.seeds != null && (config.seeds.isEmpty() || config.seeds.contains(null))) {
            throw new IllegalArgumentException("seeds must be a nonempty array of integers");
        }
        final var algorithms = new LinkedHashSet<Selection>();
        for (final var algorithm : config.algorithms) {
            if (algorithm == null) {
                throw new IllegalArgumentException("Algorithm settings cannot be null");
            }
            algorithm.settings(null, bulkSize);
            final String prior = algorithm.rolloutPrior() == null ? null
                    : path.getParent().resolve(algorithm.rolloutPrior()).normalize().toString();
            if (config.seeds == null) {
                algorithms.add(new Selection(algorithm.settings(null, bulkSize), prior));
            } else {
                for (final var seed : config.seeds) {
                    algorithms.add(new Selection(algorithm.settings(seed, bulkSize), prior));
                }
            }
        }
        final var defaults = Options.defaults();
        return new Plan(List.copyOf(datasets), List.copyOf(algorithms), bound(config.forks, 2, 1, 20, "forks"),
                bound(config.warmups, 1, 0, 100, "warmups"),
                bound(config.measurements, 3, 1, 100, "measurements"),
                bound(config.timeoutSeconds, 3600, 1, 86400, "timeoutSeconds"), gpuMode, bulkSize,
                new Options(or(config.maxOperations, defaults.maxOperations()),
                        or(config.catalogueLimit, defaults.catalogueLimit()), or(config.probeCap, defaults.probeCap()),
                        or(config.inflationAttempts, defaults.inflationAttempts()),
                        config.inflationSeed == null ? defaults.inflationSeed() : config.inflationSeed));
    }

    private static void keys(JsonObject object, Set<String> allowed) {
        for (final var key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown benchmark option: " + key);
            }
        }
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int bound(Integer value, int fallback, int min, int max, String name) {
        final int actual = or(value, fallback);
        if (actual < min || actual > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return actual;
    }

    private static Summary summary(List<Measurement> samples) {
        final var creates = samples.stream().filter(sample -> sample.scheduleWindows().count() > 0).toList();
        final var removes = samples.stream().filter(sample -> sample.removals().count() > 0).toList();
        return new Summary(distribution(samples, Measurement::replayNanos),
                distribution(samples, Measurement::eventsPerSecond),
                distribution(creates, sample -> sample.scheduleWindows().p50Nanos()),
                distribution(creates, sample -> sample.scheduleWindows().p95Nanos()),
                distribution(creates, sample -> sample.scheduleWindows().p99Nanos()),
                distribution(removes, sample -> sample.removals().p99Nanos()));
    }

    private static Distribution distribution(List<Measurement> samples, ToDoubleFunction<Measurement> metric) {
        final var values = samples.stream().mapToDouble(metric).sorted().toArray();
        final int n = values.length;
        return n == 0 ? new Distribution(0, null, null, null)
                : new Distribution(n, values[0], (values[(n - 1) / 2] + values[n / 2]) / 2, values[n - 1]);
    }

    private static String tail(Path path) throws IOException {
        try (final var file = new RandomAccessFile(path.toFile(), "r")) {
            final var bytes = new byte[(int) Math.min(4096, file.length())];
            file.seek(file.length() - bytes.length);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
