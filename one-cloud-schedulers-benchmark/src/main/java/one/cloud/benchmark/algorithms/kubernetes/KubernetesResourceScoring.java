package one.cloud.benchmark.algorithms.kubernetes;

import java.util.Objects;

import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

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
    private final Strategy strategy;
    private final double[] cpuCapacity, memoryCapacity;
    private final double[] hostRemaining = new double[MEM_MIB_VALUE + 1];

    public KubernetesResourceScoring(SchedulingContext context, long seed, Strategy strategy) {
        super(context, seed);
        this.strategy = Objects.requireNonNull(strategy);
        planner = new GreedyPlanner(context);
        final var model = context.model();
        cpuCapacity = new double[model.hostCount + 1];
        memoryCapacity = new double[cpuCapacity.length];
        final var allocations = model.allocations;
        for (int host = 1; host < cpuCapacity.length; host++) {
            final int profile = model.alloc[host];
            for (int index = allocations.offset[profile]; index < allocations.offset[profile + 1]; index++) {
                if (allocations.type[index] == VCORES_VALUE) {
                    cpuCapacity[host] = allocations.amount[index];
                } else if (allocations.type[index] == MEM_MIB_VALUE) {
                    memoryCapacity[host] = allocations.amount[index];
                }
            }
        }
    }

    @Override
    public void schedule(Request request) {
        Assignment[] best = null;
        int bestScore = -1;
        int ties = 0;
        for (int host = 1; host < cpuCapacity.length; host++) {
            final double cpu = cpuCapacity[host];
            final double memory = memoryCapacity[host];
            final int before = strategy == Strategy.BALANCED_ALLOCATION && cpu > 0 && memory > 0
                    ? balance(cpu, memory, context.available(host, VCORES_VALUE), context.available(host, MEM_MIB_VALUE))
                    : MAX_SCORE;
            Assignment[] hostBest = null;
            int hostScore = -1;
            for (final int numa : planner.numaNodes(host)) {
                final var plans = planner.plan(request, host, numa, hostRemaining);
                if (plans == null) {
                    continue;
                }
                final int score = score(cpu, memory, before);
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

    private int score(double cpu, double memory, int before) {
        final double cpuRemaining = hostRemaining[VCORES_VALUE];
        final double memoryRemaining = hostRemaining[MEM_MIB_VALUE];
        if (strategy == Strategy.BALANCED_ALLOCATION) {
            final int after = balance(cpu, memory, cpuRemaining, memoryRemaining);
            return MAX_SCORE / 2 + (MAX_SCORE / 2 + after - before) / 2;
        }
        final int cpuScore = resourceScore(cpu, cpuRemaining);
        final int memoryScore = resourceScore(memory, memoryRemaining);
        if (cpuScore < 0 || memoryScore < 0) {
            return Math.max(0, Math.max(cpuScore, memoryScore));
        }
        final int round = strategy == Strategy.REQUESTED_TO_CAPACITY_RATIO ? 1 : 0;
        return (cpuScore + memoryScore + round) / 2;
    }

    private int resourceScore(double capacity, double remaining) {
        if (capacity == 0) {
            return -1;
        }
        final double amount = strategy == Strategy.LEAST_ALLOCATED ? remaining : capacity - remaining;
        final int percent = (int) (capacity > Double.MAX_VALUE / MAX_SCORE
                ? (amount / capacity) * MAX_SCORE : (amount * MAX_SCORE) / capacity);
        if (strategy == Strategy.REQUESTED_TO_CAPACITY_RATIO) {
            return percent <= PEAK_UTILIZATION
                    ? EMPTY_SCORE + (MAX_SCORE - EMPTY_SCORE) * percent / PEAK_UTILIZATION
                    : MAX_SCORE + (FULL_SCORE - MAX_SCORE) * (percent - PEAK_UTILIZATION) / (100 - PEAK_UTILIZATION);
        }
        return percent;
    }

    private static int balance(double cpu, double memory, double cpuRemaining, double memoryRemaining) {
        if (cpu == 0 || memory == 0) {
            return MAX_SCORE;
        }
        final double cpuUsed = (cpu - cpuRemaining) / cpu;
        final double memoryUsed = (memory - memoryRemaining) / memory;
        return (int) ((1 - Math.abs(cpuUsed - memoryUsed) / 2) * MAX_SCORE);
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }
}
