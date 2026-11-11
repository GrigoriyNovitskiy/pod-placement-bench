package one.cloud.benchmark.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;

import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fgd.Fgd;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.runner.DatasetRunner;
import one.cloud.benchmark.runner.Scenario;

public final class RunnerCli {
    private static final String USAGE = """
            Usage: pod-placement-bench --dataset DIR --algorithm fitcount|mean-fitcount|geometric-best-fit|fgd [options]
              DIR contains snapshot.pb and operations.pb.
              --seed N              Algorithm seed (default: 42)
              --fit-cap N           Copy cap for both fit-count variants and quality probes (default: 10)
              --cache-rows N        Resident rows for both fit-count variants (default: 256)
              --max-operations N    Positive normalized-event prefix; omitted runs the full scenario
              --catalogue-limit N   Positive evenly sampled initial-unit limit; omitted uses all units
              --gpu-model MODEL     devices20 (default, synthetic GPU devices) or aggregate
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
        try {
            options = parse(arguments);
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
                scenario = Scenario.from(dataset, options.gpuMode);
            }
            final long prepared = System.nanoTime();
            final DatasetRunner.Factory factory = switch (options.algorithm) {
                case "fitcount" -> (context, seed, catalogue) -> new FitCount(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "mean-fitcount" -> (context, seed, catalogue) -> FitCount.weighted(context, seed, catalogue,
                        options.fitCap, options.cacheRows);
                case "geometric-best-fit" -> (context, seed, catalogue) -> new GeometricBestFit(context, seed);
                case "fgd" -> Fgd::new;
                default -> throw new IllegalArgumentException("Unknown algorithm: " + options.algorithm);
            };
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
        if (!algorithm.equals("fitcount") && !algorithm.equals("mean-fitcount")
                && !algorithm.equals("geometric-best-fit") && !algorithm.equals("fgd")) {
            throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        }
        return new Arguments(dataset, algorithm, seed, fitCap, cacheRows, maxOperations, catalogueLimit, gpuMode);
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
        out.append("{\n  \"format_version\":1,\n  \"measurement\":\"single-process diagnostic replay\",")
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
                .append("\n  \"creates\":{\"attempted_units\":").append(result.scheduling().count())
                .append(",\"admitted_units\":").append(result.placedUnits())
                .append(",\"declined_units\":").append(result.scheduling().count() - result.placedUnits())
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

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private record Arguments(Path dataset, String algorithm, long seed, int fitCap,
            int cacheRows, int maxOperations, int catalogueLimit, ClusterSpec.GpuMode gpuMode) {
    }
}
