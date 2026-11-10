package one.cloud.benchmark.algorithms.bestfit;

import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class BestFit extends SchedulingAlgorithm {
    private final GreedyPlanner planner;
    private final double[][] hostCapacity;
    private final double[] remaining;

    public BestFit(SchedulingContext context, long seed) {
        super(context, seed);
        if (context.revision() != 0) {
            throw new IllegalArgumentException("Best Fit requires a fresh, empty scheduling context");
        }
        final var model = context.model();
        planner = new GreedyPlanner(context);
        hostCapacity = new double[model.hostCount + 1][model.dimensionCount + 1];
        remaining = new double[model.dimensionCount + 1];
        for (int host = 1; host <= model.hostCount; host++) {
            for (int dimension = 1; dimension <= model.dimensionCount; dimension++) {
                final double total = context.available(host, dimension);
                if (Double.isFinite(total) && total > 0) {
                    hostCapacity[host][dimension] = total;
                }
            }
        }
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int ties = 0;
        for (int host = 1; host < hostCapacity.length; host++) {
            for (final int numa : planner.numaNodes(host)) {
                final var plan = planner.plan(request, host, numa, remaining);
                if (plan == null) {
                    continue;
                }
                final double score = score(hostCapacity[host], remaining);
                if (score < bestScore) {
                    best = plan;
                    bestScore = score;
                    ties = 1;
                } else if (score == bestScore && random.nextInt(++ties) == 0) {
                    best = plan;
                }
            }
        }
        if (best != null) {
            context.place(request, best);
        }
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }

    private static double score(double[] capacity, double[] remaining) {
        double score = 0;
        for (int dimension = 1; dimension < capacity.length; dimension++) {
            if (capacity[dimension] > 0) {
                score += remaining[dimension] / capacity[dimension];
            }
        }
        return score;
    }
}
