package one.cloud.benchmark.engine;

import java.util.Objects;
import java.util.SplittableRandom;

import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public abstract class SchedulingAlgorithm implements AutoCloseable {
    protected final SchedulingContext context;
    protected final SplittableRandom random;

    protected SchedulingAlgorithm(SchedulingContext context, long seed) {
        this.context = Objects.requireNonNull(context);
        random = new SplittableRandom(seed);
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

    public void placed(Assignment placement) {
    }

    public void removed(Assignment placement) {
    }

    @Override
    public void close() throws Exception {
    }
}
