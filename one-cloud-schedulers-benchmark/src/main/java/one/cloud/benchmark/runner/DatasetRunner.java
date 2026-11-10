package one.cloud.benchmark.runner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    }

    public record Options(long seed, int eventLimit, int catalogueLimit, int probeCap) {
        public Options {
            if (eventLimit < 0 || catalogueLimit < 0 || probeCap <= 0) {
                throw new IllegalArgumentException("Operation/catalogue limits must be nonnegative and probe cap positive");
            }
        }

        public static Options defaults() {
            return new Options(42, 0, 0, 10);
        }
    }

    public record Latency(int count, long totalNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {
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
            Latency scheduling, Latency removal, List<Checkpoint> checkpoints) {
    }

    public static Result run(Scenario scenario, Options options, Factory factory) throws Exception {
        return new Replay(Objects.requireNonNull(scenario), Objects.requireNonNull(options))
                .run(Objects.requireNonNull(factory));
    }

    private static final class Replay {
        private final Scenario scenario;
        private final Options options;
        private final Request[] catalogue, probes;
        private final long catalogueChecksum, probeChecksum;
        private final int limit;
        private final boolean[] desired, admitted;
        private final int[] hostByResource, hostUse;
        private final long[] scheduleTimes, removeTimes;
        private final ArrayList<Checkpoint> checkpoints = new ArrayList<>();
        private final long started = System.nanoTime();
        private final long heapBefore = heapUsed();
        private Engine engine;
        private GreedyPlanner probePlanner;
        private int scheduleCount, removeCount, activeUnits, logicalUnits, usedHosts;
        private int peakActiveUnits, peakUsedHosts;
        private long activeResources, logicalResources, peakActiveResources;
        private long placedUnits, attemptedResources, placedResources, removedUnits, skippedRemovals;
        private long scheduleNanos, removeNanos, qualityNanos;
        private long checksum = HASH_START, sampledPeakHeap = heapBefore;

        private Replay(Scenario scenario, Options options) {
            this.scenario = scenario;
            this.options = options;
            limit = options.eventLimit == 0 ? scenario.events.length : Math.min(options.eventLimit, scenario.events.length);
            if (8L * limit > Runtime.getRuntime().maxMemory() / 4) {
                throw new IllegalArgumentException("Timing buffers exceed a quarter of the configured heap");
            }
            catalogue = sample(scenario.requests, options.catalogueLimit);
            probes = sample(catalogue, 16);
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
        }

        private Result run(Factory factory) throws Exception {
            engine = new Engine(scenario.model);
            final var algorithmCatalogue = catalogue.clone();
            final long algorithmStart = System.nanoTime();
            try (final var algorithm = Objects.requireNonNull(factory.create(engine, options.seed, algorithmCatalogue))) {
                final long replayStart = System.nanoTime();
                if (engine.revision() != 0) {
                    throw new IllegalArgumentException("Algorithm initialization changed the fresh engine");
                }
                int checkpoint = 0;
                for (int index = 0; index < limit; index++) {
                    final int event = scenario.events[index];
                    if (event == 0 || event == Integer.MIN_VALUE) {
                        throw new IllegalArgumentException("Invalid scenario event");
                    }
                    final int unit = Math.abs(event) - 1;
                    if (unit >= scenario.requests.length) {
                        throw new IllegalArgumentException("Scenario unit is out of range");
                    }
                    final var request = scenario.requests[unit];
                    checksum = mix(mix(checksum, index), event);
                    if (event > 0) {
                        create(algorithm, unit, request);
                    } else {
                        remove(unit, request);
                    }
                    final int completed = index + 1;
                    if (checkpoint < scenario.checkpoints.length && scenario.checkpoints[checkpoint] == completed) {
                        checkpoint(completed);
                        checkpoint++;
                    }
                }
                if (limit < scenario.events.length && logicalUnits > 0
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
                        latency(removeTimes, removeCount, removeNanos), List.copyOf(checkpoints));
            }
        }

        private void create(SchedulingAlgorithm algorithm, int unit, Request request) throws Exception {
            if (desired[unit]) {
                throw new IllegalArgumentException("Repeated scenario creation");
            }
            desired[unit] = true;
            logicalUnits++;
            logicalResources += request.size();
            attemptedResources += request.size();
            final long start = System.nanoTime();
            engine.schedule(algorithm, request);
            final long elapsed = System.nanoTime() - start;
            scheduleTimes[scheduleCount++] = elapsed;
            scheduleNanos = Math.addExact(scheduleNanos, elapsed);
            final boolean placed = engine.placement(request.resource(0)) != null;
            checksum = mix(checksum, placed ? 1 : 0);
            for (int index = 0; index < request.size(); index++) {
                final int resource = request.resource(index);
                final var plan = engine.placement(resource);
                if ((plan != null) != placed) {
                    throw new IllegalStateException("Engine committed a partial scheduling unit");
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
            }
            admitted[unit] = false;
            removedUnits++;
            activeUnits--;
            activeResources -= request.size();
        }

        private void checkpoint(int operation) {
            final long start = System.nanoTime();
            if (probePlanner == null && probes.length != 0) {
                probePlanner = new GreedyPlanner(engine);
            }
            final long[] fitCounts = new long[probes.length];
            for (int index = 0; index < probes.length; index++) {
                for (int host = 1; host <= scenario.model.hostCount; host++) {
                    fitCounts[index] = Math.addExact(fitCounts[index], probePlanner.fitCount(probes[index], host, options.probeCap));
                }
            }
            final long elapsed = System.nanoTime() - start;
            qualityNanos = Math.addExact(qualityNanos, elapsed);
            final long heap = heapUsed();
            sampledPeakHeap = Math.max(sampledPeakHeap, heap);
            checkpoints.add(new Checkpoint(operation, logicalUnits, logicalResources, activeUnits,
                    activeResources, usedHosts, fitCounts, elapsed, heap));
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

    private static Latency latency(long[] values, int count, long total) {
        if (count == 0) {
            return new Latency(0, 0, 0, 0, 0, 0);
        }
        Arrays.sort(values, 0, count);
        return new Latency(count, total, quantile(values, count, .50), quantile(values, count, .95),
                quantile(values, count, .99), values[count - 1]);
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
