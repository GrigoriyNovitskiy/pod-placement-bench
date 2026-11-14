package one.cloud.benchmark.runner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import one.cloud.benchmark.algorithms.fitcount.Catalogue;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class DatasetRunner {
    private static final long HASH_START = 0xcbf29ce484222325L;

    private DatasetRunner() {
    }

    @FunctionalInterface
    public interface Factory {
        /** The catalogue array belongs to the algorithm; its requests are immutable. */
        SchedulingAlgorithm create(SchedulingContext context, long seed, Request[] catalogue) throws Exception;

        default SchedulingAlgorithm create(SchedulingContext context, long seed, Catalogue catalogue) throws Exception {
            if (catalogue.hasFullFrequencies()) {
                throw new IllegalArgumentException("Full catalogue weights require a catalogue-aware factory");
            }
            return create(context, seed, catalogue.requests());
        }

        static Factory withCatalogue(CatalogueFactory factory) {
            Objects.requireNonNull(factory);
            return new Factory() {
                @Override
                public SchedulingAlgorithm create(SchedulingContext context, long seed, Request[] catalogue) throws Exception {
                    return factory.create(context, seed, Catalogue.sampled(catalogue));
                }

                @Override
                public SchedulingAlgorithm create(SchedulingContext context, long seed, Catalogue catalogue) throws Exception {
                    return factory.create(context, seed, catalogue);
                }
            };
        }
    }

    @FunctionalInterface
    public interface CatalogueFactory {
        SchedulingAlgorithm create(SchedulingContext context, long seed, Catalogue catalogue) throws Exception;
    }

    public record Options(long seed, int eventLimit, int catalogueLimit, int probeCap, boolean fullCatalogueWeights) {
        public Options {
            if (eventLimit < 0 || catalogueLimit < 0 || probeCap <= 0) {
                throw new IllegalArgumentException("Operation/catalogue limits must be nonnegative and probe cap positive");
            }
        }

        public Options(long seed, int eventLimit, int catalogueLimit, int probeCap) {
            this(seed, eventLimit, catalogueLimit, probeCap, false);
        }

        public static Options defaults() {
            return new Options(42, 0, 0, 10);
        }
    }

    public record Latency(int count, long totalNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos,
            Long p90Nanos) {
        /** Legacy summaries cannot recover p90 for nonempty measurements. */
        public Latency(int count, long totalNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {
            this(count, totalNanos, p50Nanos, p95Nanos, p99Nanos, maxNanos, count == 0 ? 0L : null);
        }
    }

    public record Checkpoint(int operation, int logicalUnits, long logicalResources, int activeUnits,
            long activeResources, int usedHosts, long[] fitCounts, long elapsedNanos, long heapUsedBytes) {
    }

    public record Result(Options options, String scenarioDigest, String algorithmClass, int processedOperations,
            int catalogueSize, long catalogueChecksum, int probeCount, long probeChecksum,
            long initializationNanos, long algorithmInitializationNanos, long replayNanos, long qualityNanos,
            long placedUnits, long attemptedResources, long placedResources, long removedUnits, long skippedRemovals,
            int peakActiveUnits, long peakActiveResources, int peakUsedHosts,
            long plannerCalls, long checksum, long heapBeforeBytes, long sampledPeakHeapBytes,
            Latency scheduling, Latency removal, List<Checkpoint> checkpoints,
            long attemptedUnits, boolean truncatedWindow, Map<String, String> parameters, Map<String, Long> counters,
            double[] requestedLogical, double[] admittedLogical, long[] refusedResourcesByAllocation,
            double[] peakActiveLogical) {
        public Result(Options options, String scenarioDigest, String algorithmClass, int processedOperations,
                int catalogueSize, long catalogueChecksum, int probeCount, long probeChecksum,
                long initializationNanos, long algorithmInitializationNanos, long replayNanos, long qualityNanos,
                long placedUnits, long attemptedResources, long placedResources, long removedUnits, long skippedRemovals,
                int peakActiveUnits, long peakActiveResources, int peakUsedHosts,
                long plannerCalls, long checksum, long heapBeforeBytes, long sampledPeakHeapBytes,
                Latency scheduling, Latency removal, List<Checkpoint> checkpoints,
                long attemptedUnits, boolean truncatedWindow, Map<String, String> parameters, Map<String, Long> counters,
                double[] requestedLogical, double[] admittedLogical, long[] refusedResourcesByAllocation) {
            this(options, scenarioDigest, algorithmClass, processedOperations,
                    catalogueSize, catalogueChecksum, probeCount, probeChecksum,
                    initializationNanos, algorithmInitializationNanos, replayNanos, qualityNanos,
                    placedUnits, attemptedResources, placedResources, removedUnits, skippedRemovals,
                    peakActiveUnits, peakActiveResources, peakUsedHosts, plannerCalls, checksum,
                    heapBeforeBytes, sampledPeakHeapBytes, scheduling, removal, checkpoints,
                    attemptedUnits, truncatedWindow, parameters, counters, requestedLogical, admittedLogical,
                    refusedResourcesByAllocation, new double[0]);
        }

        public Result(Options options, String scenarioDigest, String algorithmClass, int processedOperations,
                int catalogueSize, long catalogueChecksum, int probeCount, long probeChecksum,
                long initializationNanos, long algorithmInitializationNanos, long replayNanos, long qualityNanos,
                long placedUnits, long attemptedResources, long placedResources, long removedUnits, long skippedRemovals,
                int peakActiveUnits, long peakActiveResources, int peakUsedHosts,
                long plannerCalls, long checksum, long heapBeforeBytes, long sampledPeakHeapBytes,
                Latency scheduling, Latency removal, List<Checkpoint> checkpoints) {
            this(options, scenarioDigest, algorithmClass, processedOperations,
                    catalogueSize, catalogueChecksum, probeCount, probeChecksum,
                    initializationNanos, algorithmInitializationNanos, replayNanos, qualityNanos,
                    placedUnits, attemptedResources, placedResources, removedUnits, skippedRemovals,
                    peakActiveUnits, peakActiveResources, peakUsedHosts, plannerCalls, checksum,
                    heapBeforeBytes, sampledPeakHeapBytes, scheduling, removal, checkpoints,
                    scheduling.count(), false, Map.of(), Map.of(), new double[0], new double[0], new long[0]);
        }
    }

    public static Result run(Scenario scenario, Options options, Factory factory) throws Exception {
        return run(scenario, options, factory, scenario.checkpoints, null);
    }

    @FunctionalInterface
    interface CheckpointObserver {
        void observe(Engine engine, Checkpoint checkpoint, Request[] probes, int[] hostsAtCap,
                boolean[] desired) throws Exception;
    }

    static Result run(Scenario scenario, Options options, Factory factory, int[] qualityOperations,
            CheckpointObserver observer) throws Exception {
        return new Replay(Objects.requireNonNull(scenario), Objects.requireNonNull(options), qualityOperations, observer)
                .run(Objects.requireNonNull(factory));
    }

    private static final class Replay {
        private final Scenario scenario;
        private final Options options;
        private final Request[] catalogue, probes;
        private final Catalogue algorithmCatalogue;
        private final long catalogueChecksum, probeChecksum;
        private final int limit;
        private final int[] qualityOperations;
        private final CheckpointObserver observer;
        private final boolean[] desired, admitted;
        private final int[] hostByResource, hostUse;
        private final long[] scheduleTimes, removeTimes;
        private final double[] requestedLogical, admittedLogical, activeLogical, peakActiveLogical;
        private final long[] refusedResourcesByAllocation;
        private final ArrayList<Checkpoint> checkpoints = new ArrayList<>();
        private final long started = System.nanoTime();
        private final long heapBefore = heapUsed();
        private Engine engine;
        private GreedyPlanner probePlanner;
        private int scheduleCount, removeCount, activeUnits, logicalUnits, usedHosts;
        private int peakActiveUnits, peakUsedHosts;
        private long activeResources, logicalResources, peakActiveResources;
        private long placedUnits, attemptedResources, placedResources, removedUnits, skippedRemovals;
        private long attemptedUnits;
        private boolean truncatedWindow;
        private long scheduleNanos, removeNanos, qualityNanos;
        private long checksum = HASH_START, sampledPeakHeap = heapBefore;

        private Replay(Scenario scenario, Options options, int[] qualityOperations, CheckpointObserver observer) {
            this.scenario = scenario;
            this.options = options;
            this.qualityOperations = qualityOperations;
            this.observer = observer;
            limit = options.eventLimit == 0 ? scenario.events.length : Math.min(options.eventLimit, scenario.events.length);
            if (8L * limit > Runtime.getRuntime().maxMemory() / 4) {
                throw new IllegalArgumentException("Timing buffers exceed a quarter of the configured heap");
            }
            catalogue = sample(scenario.catalogue, options.catalogueLimit);
            algorithmCatalogue = options.fullCatalogueWeights
                    ? Catalogue.full(scenario.model, scenario.catalogue, catalogue)
                    : Catalogue.sampled(catalogue);
            probes = qualityOperations != null ? sample(catalogue, 16) : new Request[0];
            catalogueChecksum = requestChecksum(catalogue);
            probeChecksum = requestChecksum(probes);
            desired = new boolean[scenario.requests.length];
            admitted = new boolean[desired.length];
            hostByResource = new int[scenario.model.alloc.length];
            hostUse = new int[scenario.model.hostCount + 1];
            int creates = 0;
            for (int index = 0; index < limit; index++) {
                creates += scenario.events[index] > 0 ? 1 : 0;
            }
            scheduleTimes = new long[creates];
            removeTimes = new long[limit - creates];
            requestedLogical = new double[scenario.model.dimensionCount + 1];
            admittedLogical = new double[requestedLogical.length];
            activeLogical = new double[qualityOperations == null ? 0 : requestedLogical.length];
            peakActiveLogical = new double[activeLogical.length];
            refusedResourcesByAllocation = new long[scenario.model.allocations.offset.length - 1];
        }

        private Result run(Factory factory) throws Exception {
            engine = new Engine(scenario.model);
            final long algorithmStart = System.nanoTime();
            try (final var algorithm = Objects.requireNonNull(factory.create(engine, options.seed, algorithmCatalogue))) {
                final long replayStart = System.nanoTime();
                if (engine.revision() != 0) {
                    throw new IllegalArgumentException("Algorithm initialization changed the fresh engine");
                }
                int checkpoint = 0;
                for (int index = 0; index < limit;) {
                    if (observer != null && Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Benchmark interrupted");
                    }
                    final int event = scenario.events[index];
                    if (event == 0 || event == Integer.MIN_VALUE) {
                        throw new IllegalArgumentException("Invalid scenario event");
                    }
                    final int unit = Math.abs(event) - 1;
                    if (unit >= scenario.requests.length) {
                        throw new IllegalArgumentException("Scenario unit is out of range");
                    }
                    final var request = scenario.requests[unit];
                    final int completed;
                    if (event > 0) {
                        final int announcedEnd = scenario.windowEnd(index);
                        completed = Math.min(announcedEnd, limit);
                        truncatedWindow |= completed != announcedEnd;
                        create(algorithm, index, completed);
                    } else {
                        checksum = mix(mix(checksum, index), event);
                        remove(unit, request);
                        completed = index + 1;
                    }
                    if (qualityOperations != null && checkpoint < qualityOperations.length
                            && qualityOperations[checkpoint] == completed) {
                        checkpoint(completed);
                        checkpoint++;
                    }
                    index = completed;
                }
                if (qualityOperations != null && limit < scenario.events.length && logicalUnits > 0
                        && (checkpoints.isEmpty() || checkpoints.getLast().operation != limit)) {
                    checkpoint(limit);
                }
                final long replayNanos = System.nanoTime() - replayStart;
                sampledPeakHeap = Math.max(sampledPeakHeap, heapUsed());
                return new Result(options, scenario.digest, algorithm.getClass().getName(), limit,
                        catalogue.length, catalogueChecksum, probes.length, probeChecksum,
                        replayStart - started, replayStart - algorithmStart, replayNanos, qualityNanos,
                        placedUnits, attemptedResources, placedResources, removedUnits, skippedRemovals,
                        peakActiveUnits, peakActiveResources, peakUsedHosts, algorithm.plannerCalls(), checksum,
                        heapBefore, sampledPeakHeap, latency(scheduleTimes, scheduleCount, scheduleNanos),
                        latency(removeTimes, removeCount, removeNanos), List.copyOf(checkpoints),
                        attemptedUnits, truncatedWindow,
                        qualityOperations == null ? Map.of() : Map.copyOf(algorithm.parameters()),
                        qualityOperations == null ? Map.of() : Map.copyOf(algorithm.counters()),
                        requestedLogical, admittedLogical, refusedResourcesByAllocation, peakActiveLogical);
            }
        }

        private void create(SchedulingAlgorithm algorithm, int first, int end) throws Exception {
            final var window = new Request[end - first];
            for (int index = first; index < end; index++) {
                final int event = scenario.events[index];
                if (event <= 0 || event > scenario.requests.length || desired[event - 1]) {
                    throw new IllegalArgumentException("Invalid or repeated scenario creation");
                }
                final var request = scenario.requests[event - 1];
                window[index - first] = request;
                desired[event - 1] = true;
                logicalUnits++;
                logicalResources += request.size();
                attemptedUnits++;
                attemptedResources += request.size();
            }
            final long start = System.nanoTime();
            engine.schedule(algorithm, window);
            final long elapsed = System.nanoTime() - start;
            scheduleTimes[scheduleCount++] = elapsed;
            scheduleNanos = Math.addExact(scheduleNanos, elapsed);
            for (final var plan : engine.drainRelocations()) {
                relocated(plan);
            }
            for (int index = first; index < end; index++) {
                final int event = scenario.events[index];
                checksum = mix(mix(checksum, index), event);
                created(event - 1, window[index - first]);
            }
        }

        private void relocated(Assignment plan) {
            final int resource = plan.resource();
            final int oldHost = hostByResource[resource];
            if (oldHost != 0 && oldHost != plan.host()) {
                if (--hostUse[oldHost] == 0) {
                    usedHosts--;
                }
                if (hostUse[plan.host()]++ == 0) {
                    usedHosts++;
                }
                hostByResource[resource] = plan.host();
            }
            checksum = assignmentChecksum(mix(checksum, -1), plan);
        }

        private void created(int unit, Request request) {
            final boolean placed = engine.placement(request.resource(0)) != null;
            checksum = mix(checksum, placed ? 1 : 0);
            for (int index = 0; index < request.size(); index++) {
                final int resource = request.resource(index);
                final var plan = engine.placement(resource);
                if ((plan != null) != placed) {
                    throw new IllegalStateException("Engine committed a partial scheduling unit");
                }
                if (qualityOperations != null) {
                    logical(resource, placed);
                }
                if (placed) {
                    hostByResource[resource] = plan.host();
                    if (hostUse[plan.host()]++ == 0) {
                        usedHosts++;
                    }
                    checksum = assignmentChecksum(checksum, plan);
                }
            }
            if (placed) {
                admitted[unit] = true;
                placedUnits++;
                placedResources += request.size();
                activeUnits++;
                activeResources += request.size();
                peakActiveUnits = Math.max(peakActiveUnits, activeUnits);
                peakActiveResources = Math.max(peakActiveResources, activeResources);
                peakUsedHosts = Math.max(peakUsedHosts, usedHosts);
            }
        }

        private void logical(int resource, boolean placed) {
            final var allocations = scenario.model.allocations;
            final int profile = scenario.model.alloc[resource];
            if (!placed) {
                refusedResourcesByAllocation[profile]++;
            }
            for (int index = allocations.offset[profile]; index < allocations.offset[profile + 1]; index++) {
                final int dimension = allocations.type[index];
                final double amount = allocations.amount[index];
                requestedLogical[dimension] = addLogical(requestedLogical[dimension], amount);
                if (placed) {
                    admittedLogical[dimension] = addLogical(admittedLogical[dimension], amount);
                    activeLogical[dimension] = addLogical(activeLogical[dimension], amount);
                    peakActiveLogical[dimension] = Math.max(peakActiveLogical[dimension], activeLogical[dimension]);
                }
            }
        }

        private double addLogical(double accumulated, double amount) {
            final double sum = accumulated + amount;
            if (!Double.isFinite(sum)) {
                throw new ArithmeticException("Logical resource totals overflowed");
            }
            return sum;
        }

        private void remove(int unit, Request request) {
            if (!desired[unit]) {
                throw new IllegalArgumentException("Scenario removes an inactive unit");
            }
            desired[unit] = false;
            logicalUnits--;
            logicalResources -= request.size();
            checksum = mix(checksum, admitted[unit] ? 1 : 0);
            if (!admitted[unit]) {
                skippedRemovals++;
                return;
            }
            final long start = System.nanoTime();
            engine.remove(request);
            final long elapsed = System.nanoTime() - start;
            removeTimes[removeCount++] = elapsed;
            removeNanos = Math.addExact(removeNanos, elapsed);
            for (int index = 0; index < request.size(); index++) {
                final int resource = request.resource(index);
                if (engine.placement(resource) != null) {
                    throw new IllegalStateException("Engine retained a removed unit member");
                }
                if (--hostUse[hostByResource[resource]] == 0) {
                    usedHosts--;
                }
                hostByResource[resource] = 0;
                if (qualityOperations != null) {
                    final var allocations = scenario.model.allocations;
                    final int profile = scenario.model.alloc[resource];
                    for (int value = allocations.offset[profile]; value < allocations.offset[profile + 1]; value++) {
                        final int dimension = allocations.type[value];
                        activeLogical[dimension] = addLogical(activeLogical[dimension], -allocations.amount[value]);
                    }
                }
            }
            admitted[unit] = false;
            removedUnits++;
            activeUnits--;
            activeResources -= request.size();
        }

        private void checkpoint(int operation) throws Exception {
            final long start = System.nanoTime();
            if (probePlanner == null && probes.length != 0) {
                probePlanner = new GreedyPlanner(engine);
            }
            final long[] fitCounts = new long[probes.length];
            final int[] hostsAtCap = observer == null ? null : new int[probes.length];
            for (int index = 0; index < probes.length; index++) {
                for (int host = 1; host <= scenario.model.hostCount; host++) {
                    final int fit = probePlanner.fitCount(probes[index], host, options.probeCap);
                    fitCounts[index] = Math.addExact(fitCounts[index], fit);
                    if (hostsAtCap != null && fit == options.probeCap) {
                        hostsAtCap[index]++;
                    }
                }
            }
            final long elapsed = System.nanoTime() - start;
            qualityNanos = Math.addExact(qualityNanos, elapsed);
            final long heap = heapUsed();
            sampledPeakHeap = Math.max(sampledPeakHeap, heap);
            final var checkpoint = new Checkpoint(operation, logicalUnits, logicalResources, activeUnits,
                    activeResources, usedHosts, fitCounts, elapsed, heap);
            checkpoints.add(checkpoint);
            if (observer != null) {
                observer.observe(engine, checkpoint, probes, hostsAtCap, desired);
            }
        }
    }

    private static Request[] sample(Request[] requests, int limit) {
        final int count = limit == 0 ? requests.length : Math.min(limit, requests.length);
        final var result = new Request[count];
        for (int index = 0; index < count; index++) {
            result[index] = requests[(int) ((long) index * requests.length / count)];
        }
        return result;
    }

    static Latency latency(long[] values, int count, long total) {
        if (count == 0) {
            return new Latency(0, 0, 0, 0, 0, 0, 0L);
        }
        Arrays.sort(values, 0, count);
        return new Latency(count, total, quantile(values, count, .50), quantile(values, count, .95),
                quantile(values, count, .99), values[count - 1], quantile(values, count, .90));
    }

    private static long quantile(long[] values, int count, double percentile) {
        return values[(int) Math.ceil(count * percentile) - 1];
    }

    private static long heapUsed() {
        final var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long requestChecksum(Request[] requests) {
        long result = mix(HASH_START, requests.length);
        for (final var request : requests) {
            result = mix(result, request.size());
            for (int index = 0; index < request.size(); index++) {
                result = mix(result, request.resource(index));
            }
        }
        return result;
    }

    private static long assignmentChecksum(long hash, Assignment plan) {
        long result = mix(mix(mix(hash, plan.resource()), plan.host()), plan.numa());
        result = mix(result, plan.diskOffsets().length);
        for (final int offset : plan.diskOffsets()) {
            result = mix(result, offset);
        }
        result = mix(result, plan.devices().length);
        for (final int device : plan.devices()) {
            result = mix(result, device);
        }
        if (plan.gpus().length != 0) {
            result = mix(result, plan.gpus().length);
            for (final int gpu : plan.gpus()) {
                result = mix(result, gpu);
            }
        }
        return result;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
