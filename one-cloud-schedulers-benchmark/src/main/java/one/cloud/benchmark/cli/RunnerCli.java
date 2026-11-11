package one.cloud.benchmark.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import one.cloud.benchmark.algorithms.fgd.Fgd;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.BulkFitCount;
import one.cloud.benchmark.algorithms.fitcount.Rollout;
import one.cloud.benchmark.algorithms.fitcount.RolloutPrior;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.runner.DatasetRunner;
import one.cloud.benchmark.runner.Scenario;

public final class RunnerCli {
    private static final String USAGE = """
            Usage: pod-placement-bench --dataset DIR --algorithm NAME [options]
              Algorithms: fitcount, mean-fitcount, geometric-best-fit, fgd, cpu-ram-fgd,
                          k8s-least-allocated, k8s-most-allocated, k8s-requested-to-capacity-ratio,
                          k8s-balanced-allocation, bulk-fitcount, dp, rollout.
              DIR contains snapshot.pb and operations.pb.
              --seed N              Algorithm seed (default: 42)
              --fit-cap N           Copy cap (new bulk/DP/rollout modes: 128; existing modes: 10)
              --cache-rows N        Resident rows for both fit-count variants (default: 256)
              --max-operations N    Positive normalized-event prefix; omitted runs the full scenario
              --catalogue-limit N   Positive evenly sampled initial-unit limit; omitted uses all units
              --gpu-model MODEL     devices20 (default, synthetic GPU devices) or aggregate
              --bulk-size N         Announce consecutive same-service creates together (1..100; default: 1)
              --rollout-prior FILE  Required rollout-prior-v1 protobuf manifest for rollout
              --rollout-candidates N  Current candidate limit (default: 8)
              --rollout-scenarios N   Common continuation paths (default: 8)
              --rollout-horizon N     Future atomic creates per path (default: 8)
              --help                Show this help
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
        if (arguments.length == 1 && arguments[0].equals("--help")) {
            output.print(USAGE);
            return 0;
        }
        final Arguments options;
        final DatasetRunner.Factory factory;
        try {
            options = parse(arguments);
            factory = switch (options.algorithm) {
                case "fitcount" -> (context, seed, catalogue) -> new FitCount(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "mean-fitcount" -> (context, seed, catalogue) -> FitCount.weighted(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "bulk-fitcount" -> (context, seed, catalogue) -> new BulkFitCount(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "dp" -> (context, seed, catalogue) -> BulkFitCount.dynamicProgramming(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "rollout" -> (context, seed, catalogue) -> new Rollout(context, seed, catalogue,
                        RolloutPrior.load(options.prior, context.model(), options.horizon), options.fitCap,
                        options.cacheRows, options.candidates, options.scenarios, options.horizon);
                case "geometric-best-fit" -> (context, seed, catalogue) -> new GeometricBestFit(context, seed);
                case "fgd" -> Fgd::new;
                case "cpu-ram-fgd" -> Fgd::cpuRam;
                case "k8s-least-allocated" -> (context, seed, catalogue) -> new KubernetesResourceScoring(
                        context, seed, KubernetesResourceScoring.Strategy.LEAST_ALLOCATED);
                case "k8s-most-allocated" -> (context, seed, catalogue) -> new KubernetesResourceScoring(
                        context, seed, KubernetesResourceScoring.Strategy.MOST_ALLOCATED);
                case "k8s-requested-to-capacity-ratio" -> (context, seed, catalogue) -> new KubernetesResourceScoring(
                        context, seed, KubernetesResourceScoring.Strategy.REQUESTED_TO_CAPACITY_RATIO);
                case "k8s-balanced-allocation" -> (context, seed, catalogue) -> new KubernetesResourceScoring(
                        context, seed, KubernetesResourceScoring.Strategy.BALANCED_ALLOCATION);
                default -> throw new IllegalArgumentException("Unknown algorithm: " + options.algorithm);
            };
        } catch (IllegalArgumentException failure) {
            errors.println(failure.getMessage());
            errors.print(USAGE);
            return 2;
        }
        try {
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
            final var result = DatasetRunner.run(scenario, new DatasetRunner.Options(options.seed,
                    options.maxOperations, options.catalogueLimit, options.fitCap), factory);
            final long completed = System.nanoTime();
            output.println(json(options, scenario, result, snapshotHash, operationsHash,
                    hashed - start, loaded - hashed, prepared - loaded, completed - start));
            return 0;
        } catch (Exception failure) {
            errors.println("Run failed: " + failure.getMessage());
            return 1;
        }
    }

    private static Arguments parse(String[] arguments) {
        Path dataset = null;
        String algorithm = null;
        long seed = 42;
        int fitCap = 10;
        int cacheRows = 256;
        int maxOperations = 0;
        int catalogueLimit = 0;
        int bulkSize = 1;
        Path prior = null;
        int candidates = 8;
        int scenarios = 8;
        int horizon = 8;
        var gpuMode = ClusterSpec.GpuMode.DEVICES_20;
        final var seen = new HashSet<String>();
        for (int index = 0; index < arguments.length; index += 2) {
            final var flag = arguments[index];
            if (!seen.add(flag)) {
                throw new IllegalArgumentException("Repeated option: " + flag);
            }
            if (index + 1 == arguments.length || arguments[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            final var value = arguments[index + 1];
            switch (flag) {
                case "--dataset" -> dataset = Path.of(value);
                case "--algorithm" -> algorithm = value;
                case "--seed" -> {
                    try {
                        seed = Long.parseLong(value);
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("--seed must be a signed 64-bit integer", failure);
                    }
                }
                case "--fit-cap" -> fitCap = positive(flag, value);
                case "--cache-rows" -> cacheRows = positive(flag, value);
                case "--max-operations" -> maxOperations = positive(flag, value);
                case "--catalogue-limit" -> catalogueLimit = positive(flag, value);
                case "--bulk-size" -> bulkSize = positive(flag, value);
                case "--rollout-prior" -> prior = Path.of(value);
                case "--rollout-candidates" -> candidates = positive(flag, value);
                case "--rollout-scenarios" -> scenarios = positive(flag, value);
                case "--rollout-horizon" -> horizon = positive(flag, value);
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
        if (bulkSize > 100) {
            throw new IllegalArgumentException("--bulk-size must be 1..100");
        }
        if (algorithm.equals("rollout")) {
            if (prior == null || bulkSize != 1) {
                throw new IllegalArgumentException("rollout requires --rollout-prior and --bulk-size 1");
            }
        } else if (prior != null || seen.contains("--rollout-candidates")
                || seen.contains("--rollout-scenarios") || seen.contains("--rollout-horizon")) {
            throw new IllegalArgumentException("Rollout options require --algorithm rollout");
        }
        if (!seen.contains("--fit-cap") && (algorithm.equals("bulk-fitcount") || algorithm.equals("dp")
                || algorithm.equals("rollout"))) {
            fitCap = 128;
        }
        return new Arguments(dataset, algorithm, seed, fitCap, cacheRows, maxOperations, catalogueLimit, gpuMode,
                bulkSize, prior, candidates, scenarios, horizon);
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

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        final var digest = MessageDigest.getInstance("SHA-256");
        try (final var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(Arguments options, Scenario scenario, DatasetRunner.Result result,
            String snapshotHash, String operationsHash, long hashNanos, long loadNanos, long prepareNanos, long totalNanos) {
        final var out = new StringBuilder(4096);
        out.append("{\n  \"format_version\":2,\n  \"measurement\":\"single-process diagnostic replay\",")
                .append("\n  \"algorithm\":\"").append(options.algorithm).append("\",")
                .append("\n  \"algorithm_class\":\"").append(result.algorithmClass()).append("\",")
                .append("\n  \"gpu_model\":\"").append(options.gpuMode == ClusterSpec.GpuMode.AGGREGATE
                        ? "aggregate" : "devices20").append("\",")
                .append("\n  \"dataset_id\":\"").append(Long.toUnsignedString(scenario.model.id)).append("\",")
                .append("\n  \"snapshot_sha256\":\"").append(snapshotHash).append("\",")
                .append("\n  \"operations_sha256\":\"").append(operationsHash).append("\",")
                .append("\n  \"scenario_digest\":\"").append(result.scenarioDigest()).append("\",")
                .append("\n  \"source_operations_digest\":\"").append(scenario.sourceOpsDigest).append("\",")
                .append("\n  \"normalization\":\"").append(Scenario.NORMALIZATION_VERSION).append("\",")
                .append("\n  \"java\":\"").append(Runtime.version()).append("\",")
                .append("\n  \"seed\":\"").append(options.seed).append('"')
                .append(",\n  \"copy_cap\":").append(options.fitCap)
                .append(",\n  \"cache_rows\":").append(options.cacheRows)
                .append(",\n  \"probe_cap\":").append(result.options().probeCap())
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
        out.append(",\n  \"algorithm_parameters\":");
        strings(out, result.parameters());
        out.append(",\n  \"parameter_digest\":\"").append(parameterDigest(options, result)).append("\",")
                .append("\n  \"algorithm_counters\":");
        out.append('{');
        boolean firstCounter = true;
        for (final var entry : new TreeMap<>(result.counters()).entrySet()) {
            if (!firstCounter) {
                out.append(',');
            }
            firstCounter = false;
            string(out, entry.getKey());
            out.append(':').append(entry.getValue());
        }
        out.append("},\n  \"logical_resources\":{\"requested\":");
        vector(out, result.requestedLogical());
        out.append(",\"admitted\":");
        vector(out, result.admittedLogical());
        out.append("},\n  \"refused_resources_by_allocation_profile\":{");
        boolean firstRefusal = true;
        for (int profile = 0; profile < result.refusedResourcesByAllocation().length; profile++) {
            final long count = result.refusedResourcesByAllocation()[profile];
            if (count != 0) {
                if (!firstRefusal) {
                    out.append(',');
                }
                firstRefusal = false;
                out.append('"').append(profile).append("\":").append(count);
            }
        }
        out.append("},\n  \"result_checksum\":\"").append(hex(result.checksum())).append("\",")
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

    private static void strings(StringBuilder out, Map<String, String> values) {
        out.append('{');
        boolean first = true;
        for (final var entry : new TreeMap<>(values).entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, entry.getKey());
            out.append(':');
            string(out, entry.getValue());
        }
        out.append('}');
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            final char c = value.charAt(index);
            switch (c) {
                case '"', '\\' -> out.append('\\').append(c);
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit(c >>> shift & 15, 16));
                        }
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String parameterDigest(Arguments options, DatasetRunner.Result result) {
        final var parameters = new TreeMap<String, String>();
        parameters.putAll(result.parameters());
        parameters.put("algorithm", options.algorithm);
        parameters.put("backend_version", "placement-algorithms-v1");
        parameters.put("seed", Long.toString(options.seed));
        parameters.put("copy_cap", Integer.toString(options.fitCap));
        parameters.put("cache_rows", Integer.toString(options.cacheRows));
        parameters.put("bulk_size", Integer.toString(options.bulkSize));
        parameters.put("catalogue_limit", Integer.toString(options.catalogueLimit));
        parameters.put("max_operations", Integer.toString(options.maxOperations));
        parameters.put("gpu_model", options.gpuMode.name());
        final var canonical = new StringBuilder();
        strings(canonical, parameters);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void vector(StringBuilder out, double[] amounts) {
        out.append('{');
        boolean first = true;
        for (int dimension = 1; dimension < amounts.length; dimension++) {
            if (amounts[dimension] != 0) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(dimension).append("\":").append(amounts[dimension]);
            }
        }
        out.append('}');
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private record Arguments(Path dataset, String algorithm, long seed, int fitCap,
            int cacheRows, int maxOperations, int catalogueLimit, ClusterSpec.GpuMode gpuMode,
            int bulkSize, Path prior, int candidates, int scenarios, int horizon) {
    }
}
