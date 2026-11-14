package one.cloud.benchmark.algorithms.geometric;

import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class GeometricBestFit extends SchedulingAlgorithm {
    private final SplittableRandom random;
    private final GreedyPlanner planner;
    private final ToDoubleFunction<Assignment[]> scorer;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition(
                "geometric-best-fit",
                "Geometric Best Fit",
                AlgorithmParameters.SEED_ONLY,
                (context, catalogue, parameters) -> new GeometricBestFit(
                        context,
                        parameters.get("seed").getAsLong()));
    }

    public GeometricBestFit(SchedulingContext context, long seed) {
        super(context);
        random = new SplittableRandom(seed);
        planner = new GreedyPlanner(context);
        scorer = planner.normalizedResidualL2Scorer();
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int ties = 0;
        final int hosts = context.model().hostCount;
        for (int host = 1; host <= hosts; host++) {
            for (final int numa : planner.numaNodes(host)) {
                final var plans = planner.plan(request, host, numa);
                if (plans == null) {
                    continue;
                }
                final double score = scorer.applyAsDouble(plans);
                if (score < bestScore) {
                    best = plans;
                    bestScore = score;
                    ties = 1;
                } else if (score == bestScore && random.nextInt(++ties) == 0) {
                    best = plans;
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
}
