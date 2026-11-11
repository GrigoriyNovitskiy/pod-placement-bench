package one.cloud.benchmark.algorithms.kubernetes;

import java.util.Locale;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class KubernetesResourceScoring extends SchedulingAlgorithm {
    public enum Strategy {
        LEAST_ALLOCATED,
        MOST_ALLOCATED,
        REQUESTED_TO_CAPACITY_RATIO,
        BALANCED_ALLOCATION
    }

    private static final int MAX_SCORE = 100;
    private static final int PEAK_UTILIZATION = 85;
    private static final int EMPTY_SCORE = 10;
    private static final int FULL_SCORE = 50;

    private final GreedyPlanner planner;
    private final ToDoubleFunction<Assignment[]> scorer;

    public static AlgorithmDefinition definition(Strategy strategy) {
        final var suffix = strategy.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return new AlgorithmDefinition("k8s-" + suffix, "K8s " + suffix, AlgorithmParameters.SEED_ONLY,
                (context, catalogue, parameters) -> new KubernetesResourceScoring(
                        context,
                        parameters.get("seed").getAsLong(),
                        strategy));
    }

    public KubernetesResourceScoring(SchedulingContext context, long seed, Strategy strategy) {
        super(context, seed);
        Objects.requireNonNull(strategy);
        planner = new GreedyPlanner(context);
        scorer = planner.resourceFamilyScorer((remaining, total) -> resourceScore(strategy, remaining, total),
                (before, after) -> combine(strategy, before, after), strategy == Strategy.BALANCED_ALLOCATION);
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        int bestScore = -1;
        int ties = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            Assignment[] hostBest = null;
            int hostScore = -1;
            for (final int numa : planner.numaNodes(host)) {
                final var plans = planner.plan(request, host, numa);
                if (plans == null) {
                    continue;
                }
                final int score = (int) scorer.applyAsDouble(plans);
                if (score > hostScore) {
                    hostBest = plans;
                    hostScore = score;
                }
            }
            if (hostBest == null) {
                continue;
            }
            if (hostScore > bestScore) {
                best = hostBest;
                bestScore = hostScore;
                ties = 1;
            } else if (hostScore == bestScore && random.nextInt(++ties) == 0) {
                best = hostBest;
            }
        }
        if (best != null) {
            context.place(request, best);
        }
    }

    private static double combine(Strategy strategy, double[] before, double[] after) {
        if (strategy == Strategy.BALANCED_ALLOCATION) {
            return MAX_SCORE / 2 + (MAX_SCORE / 2 + balance(after) - balance(before)) / 2;
        }
        double sum = 0;
        int count = 0;
        for (final double value : after) {
            if (!Double.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        final double mean = sum / count;
        return strategy == Strategy.REQUESTED_TO_CAPACITY_RATIO ? Math.round(mean) : (int) mean;
    }

    private static double resourceScore(Strategy strategy, double remaining, double total) {
        final double used = total - remaining;
        if (strategy == Strategy.BALANCED_ALLOCATION) {
            return used / total;
        }
        final double amount = strategy == Strategy.LEAST_ALLOCATED ? remaining : used;
        final int percent = (int) (total > Double.MAX_VALUE / MAX_SCORE
                ? (amount / total) * MAX_SCORE : (amount * MAX_SCORE) / total);
        if (strategy == Strategy.REQUESTED_TO_CAPACITY_RATIO) {
            return percent <= PEAK_UTILIZATION
                    ? EMPTY_SCORE + (MAX_SCORE - EMPTY_SCORE) * percent / PEAK_UTILIZATION
                    : MAX_SCORE + (FULL_SCORE - MAX_SCORE) * (percent - PEAK_UTILIZATION) / (100 - PEAK_UTILIZATION);
        }
        return percent;
    }

    private static int balance(double[] utilization) {
        double sum = 0;
        int count = 0;
        for (final double value : utilization) {
            if (!Double.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        if (count <= 1) {
            return MAX_SCORE;
        }
        final double mean = sum / count;
        double squaredDifferences = 0;
        for (final double value : utilization) {
            if (!Double.isNaN(value)) {
                final double difference = value - mean;
                squaredDifferences += difference * difference;
            }
        }
        return (int) ((1 - Math.sqrt(squaredDifferences / count)) * MAX_SCORE);
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }
}
