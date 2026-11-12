package one.cloud.benchmark.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Descriptor;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.catboost.CatBoostAlgorithm;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.profiling.AsyncProfiler;
import one.cloud.benchmark.runner.DatasetRunner;
import one.cloud.benchmark.runner.Scenario;
import one.cloud.benchmark.simulator.SimulatorServer;

public final class RunnerCli {
    private static final Gson JSON = new Gson();
    private static final String USAGE = """
            Usage: placement-bench simulator [--port N] [--no-open]
                   placement-bench algorithms [ID]
                   placement-bench benchmark --config FILE
                   placement-bench run --dataset DIR --algorithm ID [options]
              simulator             Start the interactive UI on localhost (default port: 8765)
              algorithms [ID]       Show parameter types, defaults, ranges and runnable examples
              benchmark             Run an experiment matrix in warmed, isolated JVMs
              DIR contains snapshot.pb and operations.pb.
              --param KEY=VALUE      Algorithm parameter; repeat for different keys
              --probe-cap N          Quality-probe copy cap, independent of algorithm parameters (default: 10)
              --max-operations N     Positive normalized-event prefix; omitted runs the full scenario
              --catalogue-limit N    Positive evenly sampled initial-unit limit; omitted uses all units
              --gpu-model MODEL      devices20 (default, synthetic GPU devices) or aggregate
              --bulk-size N          Announce consecutive same-service creates together (1..100; default: 1)
              --rollout-prior FILE   Checksum-verified prior artifact when required by the algorithm schema
              --profile FILE         Capture replay in .jfr or .html (requires ASYNC_PROFILER_HOME)
              --profile-event EVENT  cpu (default), alloc or wall
              --help                 Show this help
            """;

    private static final String SIMULATOR_USAGE = """
            Usage: placement-bench simulator [--port N] [--no-open]
              --port N       Local HTTP port, 0–65535; 0 selects a free port (default: 8765)
              --no-open      Print the URL without opening a browser
              --help         Show this help without starting a server
            """;

    private RunnerCli() {
    }

    public static void main(String[] arguments) {
        final int status = run(arguments, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] arguments, PrintStream output, PrintStream errors) {
        if (arguments.length > 0 && arguments[0].equals("benchmark")) {
            return BenchmarkCli.run(arguments, output, errors);
        }
        if (arguments.length == 3 && arguments[0].equals("benchmark-worker")) {
            return BenchmarkCli.worker(arguments, errors);
        }
        if (arguments.length > 0 && arguments[0].equals("simulator")) {
            if (arguments.length == 2 && arguments[1].equals("--help")) {
                output.print(SIMULATOR_USAGE);
                return 0;
            }
            return simulator(Arrays.copyOfRange(arguments, 1, arguments.length), output, errors);
        }
        if (arguments.length == 1 && (arguments[0].equals("--help") || arguments[0].equals("help"))
                || arguments.length == 2 && arguments[0].equals("run") && arguments[1].equals("--help")) {
            output.print(USAGE);
            return 0;
        }
        final Arguments options;
        try {
            if (arguments.length > 0 && arguments[0].equals("algorithms")) {
                showAlgorithms(arguments, output);
                return 0;
            }
            options = parse(arguments);
        } catch (IllegalArgumentException failure) {
            errors.println(failure.getMessage());
            errors.print(USAGE);
            return 2;
        }
        try {
            final var profiler = options.profile == null ? null : AsyncProfiler.configured();
            if (options.profile != null && (profiler == null || !profiler.supports(options.profileFormat))) {
                throw new IllegalStateException("Set ASYNC_PROFILER_HOME to an async-profiler distribution for --profile");
            }
            if (options.profile != null && Files.exists(options.profile)) {
                throw new IllegalArgumentException("Profile output already exists: " + options.profile);
            }
            final String[] loadedModelHash = new String[1];
            final Integer[] modelTopN = new Integer[1];
            final DatasetRunner.Factory factory = (context, seed, catalogue) -> {
                final var algorithm = AlgorithmRegistry.create(options.settings, context, catalogue, options.prior);
                if (algorithm instanceof CatBoostAlgorithm model) {
                    loadedModelHash[0] = model.modelSha256();
                    modelTopN[0] = model.topN();
                }
                return algorithm;
            };
            final long start = System.nanoTime();
            final var snapshot = options.dataset.resolve("snapshot.pb");
            final var operations = options.dataset.resolve("operations.pb");
            final var snapshotHash = sha256(snapshot);
            final var operationsHash = sha256(operations);
            final long hashed = System.nanoTime();
            final Scenario scenario;
            final long loaded;
            {
                final var dataset = Dataset.load(snapshot, operations);
                loaded = System.nanoTime();
                scenario = Scenario.from(dataset, options.gpuMode).withServiceBursts(options.bulkSize);
            }
            final long prepared = System.nanoTime();
            final var replayOptions = new DatasetRunner.Options(44, options.maxOperations,
                    options.catalogueLimit, options.probeCap);
            final DatasetRunner.Result result;
            if (profiler == null) {
                result = DatasetRunner.run(scenario, replayOptions, factory);
            } else {
                try (final var capture = profiler.capture(options.profileEvent, options.profileFormat,
                        () -> DatasetRunner.run(scenario, replayOptions, factory))) {
                    Files.copy(capture.file(), options.profile);
                    result = capture.value();
                }
            }
            final long completed = System.nanoTime();
            output.println(json(options, scenario, result, snapshotHash, operationsHash,
                    hashed - start, loaded - hashed, prepared - loaded, completed - start,
                    loadedModelHash[0], modelTopN[0]));
            return 0;
        } catch (Exception failure) {
            errors.println("Run failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int simulator(String[] arguments, PrintStream output, PrintStream errors) {
        try {
            SimulatorServer.launch(arguments, output, errors);
            return 0;
        } catch (IllegalArgumentException failure) {
            errors.println(failure.getMessage());
            errors.print(SIMULATOR_USAGE);
            return 2;
        } catch (Exception failure) {
            errors.println("Simulator failed: " + failure.getMessage());
            return 1;
        }
    }

    private static void showAlgorithms(String[] arguments, PrintStream output) {
        if (arguments.length > 2) {
            throw new IllegalArgumentException("algorithms accepts at most one algorithm ID");
        }
        final var descriptors = arguments.length == 2 ? java.util.List.of(descriptor(arguments[1]))
                : AlgorithmRegistry.algorithms();
        for (final var descriptor : descriptors) {
            output.println(descriptor.id() + " — " + descriptor.name());
            if (descriptor.parameters().isEmpty()) {
                output.println("  No algorithm parameters.");
            }
            final var example = new StringBuilder("  Example: placement-bench run --dataset DATASET --algorithm ")
                    .append(descriptor.id());
            for (final var parameter : descriptor.parameters()) {
                output.print("  " + parameter.key() + ": " + parameter.type()
                        + "; default=" + JSON.toJson(parameter.defaultValue()));
                if (parameter.minimum() != null || parameter.maximum() != null) {
                    output.print("; range=[" + bound(parameter.minimum()) + ", " + bound(parameter.maximum()) + "]");
                }
                if (parameter.advanced()) {
                    output.print("; advanced");
                }
                output.println(" — " + parameter.label());
                output.println("    " + parameter.description());
                final var value = parameter.key() + "=" + parameter.defaultValue().getAsString();
                example.append(" --param ").append(shellQuote(value));
            }
            if (descriptor.requiresPrior()) {
                output.println("  Required artifact: --rollout-prior FILE (checksum-verified rollout-prior-v1 manifest)");
                example.append(" --rollout-prior PRIOR.pb");
            }
            if (descriptor.maxBulkSize() < 100) {
                output.println("  Maximum --bulk-size: " + descriptor.maxBulkSize());
            }
            output.println(example);
            output.println();
        }
    }

    private static String bound(Double value) {
        return value == null ? "unbounded" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Descriptor descriptor(String id) {
        return AlgorithmRegistry.algorithms().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown algorithm: " + id));
    }

    private static Arguments parse(String[] arguments) {
        if (arguments.length == 0 || !arguments[0].equals("run")) {
            throw new IllegalArgumentException("Choose a command: simulator, algorithms, benchmark or run");
        }
        Path dataset = null;
        String algorithm = null;
        int maxOperations = 0;
        int catalogueLimit = 0;
        int probeCap = 10;
        int bulkSize = 1;
        Path prior = null;
        Path profile = null;
        AsyncProfiler.Event profileEvent = null;
        var gpuMode = ClusterSpec.GpuMode.DEVICES_20;
        final var seen = new HashSet<String>();
        final var rawParameters = new LinkedHashMap<String, String>();
        for (int index = 1; index < arguments.length; index += 2) {
            final var flag = arguments[index];
            if (!flag.equals("--param") && !seen.add(flag)) {
                throw new IllegalArgumentException("Repeated option: " + flag);
            }
            if (index + 1 == arguments.length || arguments[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            final var value = arguments[index + 1];
            switch (flag) {
                case "--dataset" -> dataset = Path.of(value);
                case "--algorithm" -> algorithm = value;
                case "--param" -> {
                    final int separator = value.indexOf('=');
                    if (separator < 1) {
                        throw new IllegalArgumentException("--param requires KEY=VALUE");
                    }
                    final var key = value.substring(0, separator);
                    if (rawParameters.putIfAbsent(key, value.substring(separator + 1)) != null) {
                        throw new IllegalArgumentException("Repeated algorithm parameter: " + key);
                    }
                }
                case "--probe-cap" -> probeCap = positive(flag, value);
                case "--bulk-size" -> bulkSize = positive(flag, value);
                case "--rollout-prior" -> prior = Path.of(value);
                case "--profile" -> profile = Path.of(value);
                case "--profile-event" -> profileEvent = AsyncProfiler.Event.parse(value);
                case "--max-operations" -> maxOperations = positive(flag, value);
                case "--catalogue-limit" -> catalogueLimit = positive(flag, value);
                case "--gpu-model" -> gpuMode = switch (value) {
                    case "aggregate" -> ClusterSpec.GpuMode.AGGREGATE;
                    case "devices20" -> ClusterSpec.GpuMode.DEVICES_20;
                    default -> throw new IllegalArgumentException("--gpu-model must be aggregate or devices20");
                };
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }
        if (dataset == null || algorithm == null) {
            throw new IllegalArgumentException("--dataset and --algorithm are required");
        }
        final var descriptor = descriptor(algorithm);
        if (bulkSize > 100 || bulkSize > descriptor.maxBulkSize()) {
            throw new IllegalArgumentException("--bulk-size must be 1.." + descriptor.maxBulkSize()
                    + " for algorithm " + descriptor.id());
        }
        if (descriptor.requiresPrior() != (prior != null)) {
            throw new IllegalArgumentException(descriptor.requiresPrior()
                    ? "Algorithm " + descriptor.id() + " requires --rollout-prior FILE"
                    : "Algorithm " + descriptor.id() + " does not accept --rollout-prior");
        }
        final var parameters = new LinkedHashMap<String, JsonPrimitive>();
        for (final var entry : rawParameters.entrySet()) {
            final var parameter = descriptor.parameters().stream().filter(value -> value.key().equals(entry.getKey()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unsupported parameter for " + descriptor.id() + ": " + entry.getKey()));
            parameters.put(entry.getKey(), parseValue(parameter, entry.getValue()));
        }
        final var settings = AlgorithmRegistry.normalize(new AlgorithmRegistry.Settings(algorithm, parameters));
        if (profileEvent != null && profile == null) {
            throw new IllegalArgumentException("--profile-event requires --profile");
        }
        return new Arguments(dataset, settings, maxOperations, catalogueLimit, gpuMode, probeCap, bulkSize, prior,
                profile, profile == null ? null : AsyncProfiler.Format.forPath(profile),
                profileEvent == null ? AsyncProfiler.Event.CPU : profileEvent);
    }

    private static JsonPrimitive parseValue(Parameter parameter, String raw) {
        return switch (parameter.type()) {
            case STRING -> new JsonPrimitive(raw);
            case BOOLEAN -> {
                if (!raw.equals("true") && !raw.equals("false")) {
                    throw new IllegalArgumentException("Parameter " + parameter.key() + " must be true or false");
                }
                yield new JsonPrimitive(Boolean.parseBoolean(raw));
            }
            case INTEGER, NUMBER -> {
                try {
                    yield new JsonPrimitive(new BigDecimal(raw));
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException("Invalid numeric parameter: " + parameter.key());
                }
            }
        };
    }

    private static int positive(String flag, String value) {
        try {
            final int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(flag + " must be a positive integer", failure);
        }
        throw new IllegalArgumentException(flag + " must be a positive integer");
    }

    static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        final var digest = MessageDigest.getInstance("SHA-256");
        try (final var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(Arguments options, Scenario scenario, DatasetRunner.Result result,
            String snapshotHash, String operationsHash, long hashNanos, long loadNanos, long prepareNanos,
            long totalNanos, String modelHash, Integer modelTopN) {
        final var out = new StringBuilder(4096);
        out.append("{\n  \"format_version\":2,\n  \"measurement\":\"single-process diagnostic replay\",")
                .append("\n  \"algorithm\":\"").append(options.settings.id()).append("\",")
                .append("\n  \"algorithm_class\":\"").append(result.algorithmClass()).append("\",");
        out.append("\n  \"algorithm_parameters\":").append(JSON.toJson(options.settings.parameters())).append(',');
        if (modelHash != null) {
            out.append("\n  \"model_sha256\":\"").append(modelHash).append("\",")
                    .append("\n  \"model_feature_schema\":\"legacy-v2-member-score-sum\",")
                    .append("\n  \"top_n\":").append(modelTopN).append(',');
        }
        out.append("\n  \"gpu_model\":\"").append(options.gpuMode == ClusterSpec.GpuMode.AGGREGATE
                        ? "aggregate" : "devices20").append("\",")
                .append("\n  \"dataset_id\":\"").append(Long.toUnsignedString(scenario.model.id)).append("\",")
                .append("\n  \"snapshot_sha256\":\"").append(snapshotHash).append("\",")
                .append("\n  \"operations_sha256\":\"").append(operationsHash).append("\",")
                .append("\n  \"scenario_digest\":\"").append(result.scenarioDigest()).append("\",")
                .append("\n  \"source_operations_digest\":\"").append(scenario.sourceOpsDigest).append("\",")
                .append("\n  \"normalization\":\"").append(Scenario.NORMALIZATION_VERSION).append("\",")
                .append("\n  \"java\":\"").append(Runtime.version()).append("\",")
                .append("\n  \"probe_cap\":").append(result.options().probeCap())
                .append(",\n  \"catalogue_limit\":").append(options.catalogueLimit)
                .append(",\n  \"catalogue_units\":").append(result.catalogueSize())
                .append(",\n  \"catalogue_checksum\":\"").append(hex(result.catalogueChecksum())).append("\",")
                .append("\n  \"probe_count\":").append(result.probeCount())
                .append(",\n  \"probe_checksum\":\"").append(hex(result.probeChecksum())).append("\",")
                .append("\n  \"raw_operations\":").append(scenario.rawOperations)
                .append(",\n  \"normalized_operations\":").append(scenario.events.length)
                .append(",\n  \"processed_operations\":").append(result.processedOperations())
                .append(",\n  \"complete\":").append(result.processedOperations() == scenario.events.length)
                .append(",\n  \"unit_count\":").append(scenario.requests.length)
                .append(",\n  \"partial_unit_restarts\":").append(scenario.partialChurn)
                .append(",\n  \"expanded_restarts\":").append(scenario.expandedRestarts)
                .append(",\n  \"max_operations\":").append(options.maxOperations)
                .append(",\n  \"announcement\":\"").append(options.bulkSize == 1 ? "single-v1" : Scenario.BURST_VERSION)
                .append("\",\n  \"bulk_size\":").append(options.bulkSize)
                .append(",\n  \"truncated_window\":").append(result.truncatedWindow())
                .append(",\n  \"timing_ms\":{\"input_hash\":").append(ms(hashNanos))
                .append(",\"load\":").append(ms(loadNanos))
                .append(",\"scenario_prepare\":").append(ms(prepareNanos))
                .append(",\"initialization_total\":").append(ms(result.initializationNanos()))
                .append(",\"algorithm_initialization\":").append(ms(result.algorithmInitializationNanos()))
                .append(",\"replay_wall\":").append(ms(result.replayNanos()))
                .append(",\"quality\":").append(ms(result.qualityNanos()))
                .append(",\"schedule_total\":").append(ms(result.scheduling().totalNanos()))
                .append(",\"remove_total\":").append(ms(result.removal().totalNanos()))
                .append(",\"cli_total\":").append(ms(totalNanos)).append("},")
                .append("\n  \"creates\":{\"attempted_units\":").append(result.attemptedUnits())
                .append(",\"schedule_windows\":").append(result.scheduling().count())
                .append(",\"latency_scope\":\"complete announced window\"")
                .append(",\"admitted_units\":").append(result.placedUnits())
                .append(",\"declined_units\":").append(result.attemptedUnits() - result.placedUnits())
                .append(",\"attempted_resources\":").append(result.attemptedResources())
                .append(",\"admitted_resources\":").append(result.placedResources())
                .append(",\"declined_resources\":").append(result.attemptedResources() - result.placedResources())
                .append(",\"latency_ms\":");
        latency(out, result.scheduling());
        out.append("},\n  \"deletes\":{\"committed_units\":").append(result.removedUnits())
                .append(",\"declined_unit_noops\":").append(result.skippedRemovals()).append(",\"latency_ms\":");
        latency(out, result.removal());
        out.append("},\n  \"peak_active_units\":").append(result.peakActiveUnits())
                .append(",\n  \"peak_active_resources\":").append(result.peakActiveResources())
                .append(",\n  \"peak_used_hosts\":").append(result.peakUsedHosts())
                .append(",\n  \"candidate_planner_calls\":");
        if (result.plannerCalls() < 0) {
            out.append("null");
        } else {
            out.append(result.plannerCalls());
        }
        out.append(",\n  \"algorithm_runtime_parameters\":").append(JSON.toJson(new TreeMap<>(result.parameters())))
                .append(",\n  \"parameter_digest\":\"").append(parameterDigest(options, result, modelHash, modelTopN)).append("\",")
                .append("\n  \"algorithm_counters\":").append(JSON.toJson(new TreeMap<>(result.counters())))
                .append(",\n  \"logical_resources\":{\"requested\":");
        vector(out, result.requestedLogical());
        out.append(",\"admitted\":");
        vector(out, result.admittedLogical());
        final var refusals = new TreeMap<Integer, Long>();
        for (int profile = 0; profile < result.refusedResourcesByAllocation().length; profile++) {
            final long count = result.refusedResourcesByAllocation()[profile];
            if (count != 0) {
                refusals.put(profile, count);
            }
        }
        out.append("},\n  \"refused_resources_by_allocation_profile\":").append(JSON.toJson(refusals));
        out.append(",\n  \"result_checksum\":\"").append(hex(result.checksum())).append("\",")
                .append("\n  \"heap\":{\"before_run_bytes\":").append(result.heapBeforeBytes())
                .append(",\"sampled_peak_used_bytes\":").append(result.sampledPeakHeapBytes())
                .append(",\"metric\":\"sampled managed heap usage, not RSS or live set\",")
                .append("\"samples\":\"before initialization, occupied checkpoints, replay end\"},")
                .append("\n  \"quality_checkpoints\":[");
        for (int index = 0; index < result.checkpoints().size(); index++) {
            if (index != 0) {
                out.append(',');
            }
            final var checkpoint = result.checkpoints().get(index);
            out.append("\n    {\"operation\":").append(checkpoint.operation())
                    .append(",\"logical_active_units\":").append(checkpoint.logicalUnits())
                    .append(",\"logical_active_resources\":").append(checkpoint.logicalResources())
                    .append(",\"admitted_active_units\":").append(checkpoint.activeUnits())
                    .append(",\"admitted_active_resources\":").append(checkpoint.activeResources())
                    .append(",\"used_hosts\":").append(checkpoint.usedHosts())
                    .append(",\"probe_fit_counts\":[");
            for (int probe = 0; probe < checkpoint.fitCounts().length; probe++) {
                if (probe != 0) {
                    out.append(',');
                }
                out.append(checkpoint.fitCounts()[probe]);
            }
            out.append("],\"probe_time_ms\":").append(ms(checkpoint.elapsedNanos()))
                    .append(",\"heap_used_bytes\":").append(checkpoint.heapUsedBytes()).append('}');
        }
        return out.append("\n  ]\n}").toString();
    }

    private static void latency(StringBuilder out, DatasetRunner.Latency latency) {
        if (latency.count() == 0) {
            out.append("{\"mean\":null,\"p50\":null,\"p95\":null,\"p99\":null,\"max\":null}");
        } else {
            out.append("{\"mean\":").append(latency.totalNanos() / (1e6 * latency.count()))
                    .append(",\"p50\":").append(ms(latency.p50Nanos()))
                    .append(",\"p95\":").append(ms(latency.p95Nanos()))
                    .append(",\"p99\":").append(ms(latency.p99Nanos()))
                    .append(",\"max\":").append(ms(latency.maxNanos())).append('}');
        }
    }

    private static String hex(long value) {
        return Long.toUnsignedString(value, 16);
    }

    private static String parameterDigest(Arguments options, DatasetRunner.Result result, String modelHash,
            Integer modelTopN) {
        final var parameters = new TreeMap<String, Object>();
        parameters.put("algorithm", options.settings.id());
        parameters.put("algorithm_class", result.algorithmClass());
        parameters.put("backend_version", "placement-algorithms-v1");
        parameters.put("algorithm_parameters", new TreeMap<>(options.settings.parameters()));
        parameters.put("algorithm_runtime_parameters", new TreeMap<>(result.parameters()));
        parameters.put("bulk_size", options.bulkSize);
        parameters.put("catalogue_limit", options.catalogueLimit);
        parameters.put("max_operations", options.maxOperations);
        parameters.put("probe_cap", options.probeCap);
        parameters.put("gpu_model", options.gpuMode.name());
        if (modelHash != null) {
            parameters.put("model_sha256", modelHash);
            parameters.put("model_top_n", modelTopN);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJson(parameters).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void vector(StringBuilder out, double[] amounts) {
        final var values = new TreeMap<Integer, Double>();
        for (int dimension = 1; dimension < amounts.length; dimension++) {
            if (amounts[dimension] != 0) {
                values.put(dimension, amounts[dimension]);
            }
        }
        out.append(JSON.toJson(values));
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private record Arguments(Path dataset, AlgorithmRegistry.Settings settings, int maxOperations,
            int catalogueLimit, ClusterSpec.GpuMode gpuMode, int probeCap, int bulkSize, Path prior, Path profile,
            AsyncProfiler.Format profileFormat, AsyncProfiler.Event profileEvent) {
    }
}
