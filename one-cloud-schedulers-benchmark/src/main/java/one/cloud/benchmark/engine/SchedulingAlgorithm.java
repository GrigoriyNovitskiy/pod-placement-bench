package one.cloud.benchmark.engine;

import java.util.Objects;
import java.util.Map;

import one.cloud.benchmark.model.Request;

public abstract class SchedulingAlgorithm implements AutoCloseable {
    protected final SchedulingContext context;

    /** Creates an algorithm with access to the shared scheduling context. */
    protected SchedulingAlgorithm(SchedulingContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Schedules each request in order. */
    public void schedule(Request[] requests) throws Exception {
        for (final var request : requests) {
            schedule(request);
        }
    }

    /** Attempts to place a request atomically using the current context. */
    public abstract void schedule(Request request) throws Exception;

    /** Candidate planner calls, or -1 when this algorithm does not report them. */
    public long plannerCalls() {
        return -1;
    }

    /** Returns the runtime settings and provenance for this algorithm. */
    public Map<String, String> parameters() {
        return Map.of();
    }

    /** Returns diagnostic counters reported by this algorithm. */
    public Map<String, Long> counters() {
        return Map.of();
    }

    /** Called after a complete state change for each host whose physical capacity changed. */
    public void capacityChanged(int host) {
    }

    /** Releases any algorithm-specific resources. */
    @Override
    public void close() throws Exception {
    }
}
