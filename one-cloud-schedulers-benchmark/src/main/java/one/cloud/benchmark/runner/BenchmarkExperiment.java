package one.cloud.benchmark.runner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.DISK_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.DEVICE_EXTENTS_VALUE;

public final class BenchmarkExperiment {
    public static final int FORMAT_VERSION = 1;

    private BenchmarkExperiment() {
    }

    public record Options(int maxOperations, int catalogueLimit, int probeCap, int inflationAttempts,
            long inflationSeed) {
        public Options {
            if (maxOperations < 0 || catalogueLimit < 0 || probeCap < 1 || probeCap > 1000
                    || inflationAttempts < 0 || inflationAttempts > 10000) {
                throw new IllegalArgumentException("Expected nonnegative operation/catalogue limits, probeCap 1..1000 "
                        + "and inflationAttempts 0..10000");
            }
        }

        public static Options defaults() {
            return new Options(0, 0, 10, 1000, 42);
        }

        DatasetRunner.Options replayOptions() {
            return new DatasetRunner.Options(44, maxOperations, catalogueLimit, probeCap);
        }
    }

    public record Algorithm(String id, Map<String, JsonPrimitive> parameters, String rolloutPrior) {
        public AlgorithmRegistry.Settings settings(Long seed, int bulkSize) {
            final var descriptor = AlgorithmRegistry.algorithms().stream().filter(value -> value.id().equals(id))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown algorithm: " + id));
            if (bulkSize > descriptor.maxBulkSize()) {
                throw new IllegalArgumentException(id + " supports bulkSize at most " + descriptor.maxBulkSize());
            }
            if (descriptor.requiresPrior() != (rolloutPrior != null && !rolloutPrior.isBlank())) {
                throw new IllegalArgumentException(descriptor.requiresPrior() ? id + " requires rolloutPrior"
                        : id + " does not accept rolloutPrior");
            }
            final var values = new LinkedHashMap<String, JsonPrimitive>();
            if (parameters != null) {
                values.putAll(parameters);
            }
            if (seed != null && descriptor.parameters().stream().anyMatch(parameter -> parameter.key().equals("seed"))) {
                values.put("seed", new JsonPrimitive(seed));
            }
            return AlgorithmRegistry.normalize(new AlgorithmRegistry.Settings(id, values));
        }
    }

    public record ScenarioInfo(String datasetId, String digest, String sourceOperationsDigest, String gpuModel,
            int rawOperations, int normalizedOperations, int bulkSize) {
        public static ScenarioInfo from(Scenario scenario) {
            return new ScenarioInfo(Long.toUnsignedString(scenario.model.id), scenario.digest, scenario.sourceOpsDigest,
                    scenario.model.gpuMode == ClusterSpec.GpuMode.AGGREGATE ? "aggregate" : "devices20",
                    scenario.rawOperations, scenario.events.length, scenario.maxBurstSize);
        }
    }

    public record ReplayCounts(int processedOperations, boolean complete, boolean truncatedWindow,
            long attemptedUnits, long admittedUnits, long declinedUnits,
            long attemptedResources, long admittedResources, long declinedResources,
            long committedRemovals, long skippedRemovals, int peakActiveUnits, long peakActiveResources,
            int peakUsedHosts, String checksum) {
        private static ReplayCounts from(Scenario scenario, DatasetRunner.Result result) {
            return new ReplayCounts(result.processedOperations(), result.processedOperations() == scenario.events.length,
                    result.truncatedWindow(), result.attemptedUnits(), result.placedUnits(),
                    result.attemptedUnits() - result.placedUnits(), result.attemptedResources(), result.placedResources(),
                    result.attemptedResources() - result.placedResources(), result.removedUnits(), result.skippedRemovals(),
                    result.peakActiveUnits(), result.peakActiveResources(), result.peakUsedHosts(),
                    Long.toUnsignedString(result.checksum(), 16));
        }
    }

    public record Measurement(ReplayCounts counts, long replayNanos, long initializationNanos,
            long algorithmInitializationNanos, double eventsPerSecond, DatasetRunner.Latency scheduleWindows,
            DatasetRunner.Latency removals) {
    }

    public record Utilization(Double capacity, Double available, Double usedOrUnavailable, Double fraction) {
    }

    public record Probe(List<Integer> memberIds, Map<String, Double> logicalDemand, long additionalCopies,
            int hostsAtCopyCap) {
    }

    public record Checkpoint(int operation, int offeredActiveUnits, long offeredActiveResources,
            int admittedActiveUnits, long admittedActiveResources, int usedHosts,
            Map<String, Double> offeredActiveDemand, Map<String, Utilization> utilization, List<Probe> holeFilling) {
    }

    public record Inflation(int operation, String status, int requestedAttempts, int eligibleTemplates,
            int excludedTemplates, String sequenceChecksum, int attemptedUnits, int admittedUnits,
            int declinedUnits, Integer firstDeclineAttempt, int admittedAfterFirstDecline,
            Map<String, Double> admittedLogical, Map<String, Utilization> utilizationAfter) {
    }

    public record Quality(ReplayCounts counts, int catalogueSize, String catalogueChecksum,
            Map<String, Double> requestedLogical, Map<String, Double> admittedLogical,
            Map<Integer, Long> refusedResourcesByAllocation, long plannerCalls, Map<String, String> parameters,
            Map<String, Long> counters, List<Checkpoint> checkpoints, Inflation inflation,
            Map<String, Double> peakActiveLogical) {
        public Quality(ReplayCounts counts, int catalogueSize, String catalogueChecksum,
                Map<String, Double> requestedLogical, Map<String, Double> admittedLogical,
                Map<Integer, Long> refusedResourcesByAllocation, long plannerCalls, Map<String, String> parameters,
                Map<String, Long> counters, List<Checkpoint> checkpoints, Inflation inflation) {
            this(counts, catalogueSize, catalogueChecksum, requestedLogical, admittedLogical,
                    refusedResourcesByAllocation, plannerCalls, parameters, counters, checkpoints, inflation, Map.of());
        }
    }

    public static DatasetRunner.Factory factory(AlgorithmRegistry.Settings settings, Path prior) {
        return (context, seed, catalogue) -> AlgorithmRegistry.create(settings, context, catalogue, prior);
    }

    public static Measurement measure(Scenario scenario, Options options, DatasetRunner.Factory factory) throws Exception {
        final var result = DatasetRunner.run(scenario, options.replayOptions(), factory, null, null);
        return new Measurement(ReplayCounts.from(scenario, result), result.replayNanos(), result.initializationNanos(),
                result.algorithmInitializationNanos(), result.processedOperations() * 1e9 / Math.max(1, result.replayNanos()),
                result.scheduling(), result.removal());
    }

    public static Quality quality(Scenario scenario, Options options, DatasetRunner.Factory factory) throws Exception {
        final var checkpoints = new ArrayList<Checkpoint>();
        final var capacity = new FleetCapacity(scenario.model);
        final Inflation[] inflation = new Inflation[1];
        final int limit = options.maxOperations == 0 ? scenario.events.length
                : Math.min(options.maxOperations, scenario.events.length);
        final int initialFill = scenario.checkpoints.length == 0 ? 0 : Math.min(limit, scenario.checkpoints[0]);
        final var result = DatasetRunner.run(scenario, options.replayOptions(), factory,
                qualityOperations(scenario, limit, initialFill),
                (engine, checkpoint, probes, hostsAtCap, desired) -> {
                    final var demand = new double[scenario.model.dimensionCount + 1];
                    for (int unit = 0; unit < desired.length; unit++) {
                        if (desired[unit]) {
                            addDemand(scenario.model, scenario.requests[unit], demand);
                        }
                    }
                    final var holeFilling = new ArrayList<Probe>(probes.length);
                    for (int index = 0; index < probes.length; index++) {
                        final var probe = probes[index];
                        final var members = new ArrayList<Integer>(probe.size());
                        for (int member = 0; member < probe.size(); member++) {
                            members.add(probe.resource(member));
                        }
                        final var probeDemand = new double[demand.length];
                        addDemand(scenario.model, probe, probeDemand);
                        holeFilling.add(new Probe(List.copyOf(members), vector(probeDemand),
                                checkpoint.fitCounts()[index], hostsAtCap[index]));
                    }
                    checkpoints.add(new Checkpoint(checkpoint.operation(), checkpoint.logicalUnits(),
                            checkpoint.logicalResources(), checkpoint.activeUnits(), checkpoint.activeResources(),
                            checkpoint.usedHosts(), vector(demand), capacity.sample(engine), List.copyOf(holeFilling)));
                    if (options.inflationAttempts > 0 && checkpoint.operation() == initialFill
                            && checkpoint.logicalUnits() > 0) {
                        inflation[0] = WorkloadInflation.run(engine, scenario.catalogue, capacity, checkpoint.operation(),
                                options.inflationAttempts, options.inflationSeed);
                    }
                });
        final var refused = new LinkedHashMap<Integer, Long>();
        for (int profile = 1; profile < result.refusedResourcesByAllocation().length; profile++) {
            if (result.refusedResourcesByAllocation()[profile] != 0) {
                refused.put(profile, result.refusedResourcesByAllocation()[profile]);
            }
        }
        return new Quality(ReplayCounts.from(scenario, result), result.catalogueSize(),
                Long.toUnsignedString(result.catalogueChecksum(), 16), vector(result.requestedLogical()),
                vector(result.admittedLogical()), refused, result.plannerCalls(), result.parameters(), result.counters(),
                List.copyOf(checkpoints), inflation[0], vector(result.peakActiveLogical()));
    }

    private static int[] qualityOperations(Scenario scenario, int limit, int initialFill) {
        final var selected = new TreeSet<Integer>();
        final int position = Arrays.binarySearch(scenario.checkpoints, limit);
        final int phases = position < 0 ? -position - 1 : position + 1;
        final int samples = Math.min(phases, 32);
        for (int index = 0; index < samples; index++) {
            selected.add(scenario.checkpoints[(int) ((long) index * (phases - 1) / Math.max(1, samples - 1))]);
        }
        int quarter = 1;
        for (int start = 0; start < initialFill && quarter < 4;) {
            final int end = scenario.windowEnd(start);
            while (quarter < 4 && 4L * end >= (long) quarter * initialFill) {
                if (end < initialFill) {
                    selected.add(end);
                }
                quarter++;
            }
            start = end;
        }
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    static void addDemand(ClusterSpec model, Request request, double[] demand) {
        final var allocations = model.allocations;
        for (int member = 0; member < request.size(); member++) {
            final int profile = model.alloc[request.resource(member)];
            for (int index = allocations.offset[profile]; index < allocations.offset[profile + 1]; index++) {
                final int dimension = allocations.type[index];
                demand[dimension] += allocations.amount[index];
                if (!Double.isFinite(demand[dimension])) {
                    throw new ArithmeticException("Logical resource demand overflow");
                }
            }
        }
    }

    static Map<String, Double> vector(double[] values) {
        final var result = new LinkedHashMap<String, Double>();
        for (int dimension = 1; dimension < values.length; dimension++) {
            if (values[dimension] != 0) {
                result.put(dimensionName(dimension), values[dimension]);
            }
        }
        return result;
    }

    private static String dimensionName(int dimension) {
        final var type = ResourceType.forNumber(dimension);
        return type == null ? "dimension-" + dimension : type.name();
    }

    static final class FleetCapacity {
        private final int[] hosts, disks;
        private final double[] total;

        FleetCapacity(ClusterSpec model) {
            final var enabledHosts = new ArrayList<Integer>();
            final var enabledDisks = new ArrayList<Integer>();
            for (int host = 1; host <= model.hostCount; host++) {
                if ((model.rules.flags[model.constraints[host]] & 1) == 0) {
                    enabledHosts.add(host);
                }
            }
            for (int resource = model.hostCount + 1; resource < model.alloc.length; resource++) {
                final int profile = model.constraints[resource];
                if (model.rules.kind[profile] == DISK_VALUE && (model.rules.flags[profile] & 1) == 0
                        && (model.rules.flags[model.constraints[model.rules.parent[profile]]] & 1) == 0) {
                    enabledDisks.add(resource);
                }
            }
            hosts = enabledHosts.stream().mapToInt(Integer::intValue).toArray();
            disks = enabledDisks.stream().mapToInt(Integer::intValue).toArray();
            total = remaining(new Engine(model));
        }

        Map<String, Utilization> sample(Engine engine) {
            final double[] free = remaining(engine);
            final var values = new LinkedHashMap<String, Utilization>();
            for (int dimension = 1; dimension < total.length; dimension++) {
                final var value = !Double.isFinite(total[dimension]) || !Double.isFinite(free[dimension])
                        ? new Utilization(null, null, null, null)
                        : new Utilization(total[dimension], free[dimension],
                                Math.max(0, total[dimension] - free[dimension]),
                                total[dimension] == 0 ? null : Math.max(0, 1 - free[dimension] / total[dimension]));
                values.put(dimensionName(dimension), value);
            }
            return values;
        }

        private double[] remaining(Engine engine) {
            final int count = engine.model().dimensionCount;
            final var result = new double[count + 1];
            for (int dimension = 1; dimension <= count; dimension++) {
                for (final int inventory : dimension < DEVICE_EXTENTS_VALUE ? hosts : disks) {
                    final double available = engine.available(inventory, dimension);
                    result[dimension] += available == Double.MAX_VALUE ? Double.POSITIVE_INFINITY : available;
                }
            }
            return result;
        }
    }
}
