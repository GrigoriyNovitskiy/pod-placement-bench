package one.cloud.benchmark.algorithms.yp;

import java.util.Map;
import java.util.SplittableRandom;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.SSD_GIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

public final class YpLight extends SchedulingAlgorithm {
    private static final int[] SCORE_DIMENSIONS = {VCORES_VALUE, MEM_MIB_VALUE, SSD_GIB_VALUE};

    private final Engine engine;
    private final GreedyPlanner planner;
    private final SplittableRandom random;
    private final double[][] totals;
    private final double[] remaining;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("yp-light", "Yandex Planner Light", AlgorithmParameters.SEED_ONLY,
                (context, catalogue, parameters) -> new YpLight(requireEngine(context), parameters.get("seed").getAsLong()));
    }

    static Engine requireEngine(SchedulingContext context) {
        if (context instanceof Engine engine) {
            return engine;
        }
        throw new IllegalArgumentException("Yandex Planner requires the placement engine");
    }

    YpLight(Engine engine, long seed) {
        super(engine);
        this.engine = engine;
        planner = new GreedyPlanner(engine);
        random = new SplittableRandom(seed);
        totals = new double[engine.model().hostCount + 1][SCORE_DIMENSIONS.length];
        remaining = new double[Math.min(SSD_GIB_VALUE, engine.model().dimensionCount) + 1];
        final var pristine = engine.revision() == 0 ? engine : new Engine(engine.model());
        for (int host = 1; host < totals.length; host++) {
            for (int i = 0; i < SCORE_DIMENSIONS.length; i++) {
                if (SCORE_DIMENSIONS[i] < remaining.length) {
                    totals[host][i] = pristine.available(host, SCORE_DIMENSIONS[i]);
                }
            }
        }
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean bestSoft = false;
        int ties = 0;
        for (int host = 1; host < totals.length; host++) {
            for (final int numa : planner.numaNodes(host)) {
                final var plans = planner.plan(request, host, numa, remaining);
                if (plans == null) {
                    continue;
                }
                final boolean soft = engine.satisfiesSoftLimits(plans);
                if (bestSoft && !soft) {
                    continue;
                }
                final double score = score(totals[host]);
                if (soft && !bestSoft || score < bestScore) {
                    best = plans;
                    bestSoft = soft;
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

    private double score(double[] total) {
        double score = 0;
        for (int i = 0; i < total.length; i++) {
            final double fraction = total[i] > 0 ? remaining[SCORE_DIMENSIONS[i]] / total[i] : 0;
            score += Math.pow(10, fraction);
        }
        return score;
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("rank", "soft-limits-then-exp-cpu-ram-ssd", "tie_break", "seeded");
    }
}
