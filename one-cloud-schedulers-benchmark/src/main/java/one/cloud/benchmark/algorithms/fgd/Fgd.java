package one.cloud.benchmark.algorithms.fgd;

import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.GpuTopology;
import one.cloud.benchmark.model.Request;

public final class Fgd extends SchedulingAlgorithm {
    private final GreedyPlanner planner;
    private final ToDoubleFunction<Assignment[]> scorer;
    private final boolean refineGpus;

    public Fgd(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, true);
    }

    public static Fgd cpuRam(SchedulingContext context, long seed, Request[] catalogue) {
        return new Fgd(context, seed, catalogue, false);
    }

    private Fgd(SchedulingContext context, long seed, Request[] catalogue, boolean refineGpus) {
        super(context, seed);
        planner = new GreedyPlanner(context);
        this.refineGpus = refineGpus;
        scorer = refineGpus ? planner.gpuFragmentationScorer(catalogue) : planner.cpuRamFragmentationScorer(catalogue);
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int ties = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            for (final int numa : planner.numaNodes(host)) {
                final var plans = planner.plan(request, host, numa);
                if (plans == null) {
                    continue;
                }
                final double score = refineGpus ? improveGpus(request, plans) : scorer.applyAsDouble(plans);
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

    private double improveGpus(Request request, Assignment[] plans) {
        final var topology = context.model().gpus;
        double score = scorer.applyAsDouble(plans);
        for (final var plan : plans) {
            final var gpus = plan.gpus();
            for (int slot = 0; slot < gpus.length; slot++) {
                final int original = gpus[slot];
                final int dimension = topology.type(original);
                final int shares = topology.demand(plan.resource(), dimension);
                if (shares >= GpuTopology.SHARES) {
                    continue;
                }
                int selected = original;
                int ties = 1;
                for (int gpu = topology.first(plan.host(), dimension); gpu < topology.end(plan.host(), dimension); gpu++) {
                    if (gpu == original || context.gpuAvailable(gpu) < shares) {
                        continue;
                    }
                    gpus[slot] = gpu;
                    if (!context.canPlace(request, plans)) {
                        continue;
                    }
                    final double candidate = scorer.applyAsDouble(plans);
                    if (candidate < score) {
                        selected = gpu;
                        score = candidate;
                        ties = 1;
                    } else if (candidate == score && random.nextInt(++ties) == 0) {
                        selected = gpu;
                    }
                }
                gpus[slot] = selected;
            }
        }
        return score;
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }
}
