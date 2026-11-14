package one.cloud.benchmark.engine;

import java.util.Objects;
import java.util.Map;

import one.cloud.benchmark.model.Request;

public abstract class SchedulingAlgorithm implements AutoCloseable {
    protected final SchedulingContext context;

    protected SchedulingAlgorithm(SchedulingContext context) {
        this.context = Objects.requireNonNull(context);
    }

    public void schedule(Request[] requests) throws Exception {
        for (final var request : requests) {
            schedule(request);
        }
    }

    public abstract void schedule(Request request) throws Exception;

    /** Candidate planner calls, or -1 when this algorithm does not report them. */
    public long plannerCalls() {
        return -1;
    }

    public Map<String, String> parameters() {
        return Map.of();
    }

    public Map<String, Long> counters() {
        return Map.of();
    }

    /** Called after a complete state change for each host whose physical capacity changed. */
    public void capacityChanged(int host) {
    }

    @Override
    public void close() throws Exception {
    }
}
