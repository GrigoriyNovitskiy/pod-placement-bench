package one.cloud.benchmark.engine;

import java.util.Objects;
import java.util.SplittableRandom;

import one.cloud.benchmark.model.Placement;
import one.cloud.benchmark.model.PlacementRequest;

public abstract class PlacementAlgorithm implements AutoCloseable {
    protected final PlacementContext context;
    protected final SplittableRandom random;

    protected PlacementAlgorithm(PlacementContext context, long seed) {
        this.context = Objects.requireNonNull(context);
        random = new SplittableRandom(seed);
    }

    /**
     * Places any subset of the announced units through {@link PlacementContext#place}, in input order.
     * The request array is read-only.
     * A unit left unplaced is a declined request, not proof of infeasibility. Unexpected backend
     * failures, cancellation and timeouts fail the call. Declared bounded optimization may return
     * a feasible incumbent under its recorded termination policy. All native, GPU or asynchronous
     * work must finish before submitting its result and returning.
     */
    public void schedule(PlacementRequest[] requests) throws Exception {
        for (final var request : requests) {
            schedule(request);
        }
    }

    public abstract void schedule(PlacementRequest request) throws Exception;

    /** Called after the entire unit is committed; the placement is borrowed and read-only. */
    public void placed(Placement placement) {
    }

    /** Called after removal and all state updates; the former placement is borrowed and read-only. */
    public void removed(Placement placement) {
    }

    @Override
    public void close() throws Exception {
    }
}
