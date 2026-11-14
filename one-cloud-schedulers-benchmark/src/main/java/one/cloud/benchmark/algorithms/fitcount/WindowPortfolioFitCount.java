package one.cloud.benchmark.algorithms.fitcount;

import java.util.List;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class WindowPortfolioFitCount extends SchedulingAlgorithm {
    private static final int MAX_WINDOW = 100;
    private static final long SNAPSHOT_BUDGET = 64L << 20;
    private final RandomNFitCount fallback;
    private final Request[] catalogue;
    private final long seed, snapshotBudget;
    private final int cap, cacheRows;
    private final boolean comparePortfolio;
    private long windows, trialWindows, trajectories, trialDecisions, skippedFull, supersetWins, rejected;
    private long replaySteps, snapshotPeak, trialPlannerCalls;
    private long oversized, trialUnavailable, incumbentWorkspace, challengerWorkspace;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("window-portfolio-fitcount", "Window portfolio + FitCount",
                List.of(AlgorithmParameters.SEED, AlgorithmParameters.FIT_CAP, AlgorithmParameters.CACHE_ROWS,
                        Parameter.bool("comparePortfolio", "Compare portfolio",
                                "Сравнить полную portfolio trajectory с Random20 baseline.", true)),
                (context, catalogue, parameters) -> new WindowPortfolioFitCount(context,
                        parameters.get("seed").getAsLong(), catalogue, parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), parameters.get("comparePortfolio").getAsBoolean()));
    }

    public WindowPortfolioFitCount(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, 10, 64, true);
    }

    public WindowPortfolioFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int cacheRows, boolean comparePortfolio) {
        this(context, seed, catalogue, cap, cacheRows, comparePortfolio, SNAPSHOT_BUDGET);
    }

    WindowPortfolioFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int cacheRows, boolean comparePortfolio, long snapshotBudget) {
        super(context);
        if (snapshotBudget < 0) {
            throw new IllegalArgumentException("Snapshot budget must be nonnegative");
        }
        this.seed = seed;
        this.catalogue = catalogue.clone();
        this.cap = cap;
        this.cacheRows = cacheRows;
        this.comparePortfolio = comparePortfolio;
        this.snapshotBudget = snapshotBudget;
        fallback = new RandomNFitCount(context, seed, catalogue, 20, cap, cacheRows);
    }

    @Override
    public void schedule(Request request) {
        windows++;
        fallback.schedule(request);
    }

    @Override
    public void schedule(Request[] requests) throws Exception {
        if (requests.length == 0) {
            return;
        }
        final long window = windows++;
        if (requests.length == 1) {
            fallback.schedule(requests[0]);
            return;
        }
        if (requests.length > MAX_WINDOW) {
            oversized++;
            fallback.schedule(requests);
            return;
        }
        final Engine.Trial trial;
        try {
            trial = context.trial(context.model());
        } catch (UnsupportedOperationException unavailable) {
            trialUnavailable++;
            fallback.schedule(requests);
            return;
        }
        trialWindows++;
        final long windowSeed = PortfolioFitCount.mix64(seed ^ PortfolioFitCount.mix64(window));
        Assignment[][] winner = null;
        try {
            final var incumbent = trajectory(trial, requests, windowSeed, false, 0);
            winner = incumbent.plans;
            if (incumbent.admitted == requests.length) {
                skippedFull++;
            } else if (comparePortfolio) {
                trial.reset();
                try {
                    final var challenger = trajectory(trial, requests, windowSeed, true, incumbent.bytes);
                    if (strictSuperset(incumbent.plans, challenger.plans)) {
                        winner = challenger.plans;
                        supersetWins++;
                    } else {
                        rejected++;
                    }
                } catch (SnapshotLimit limit) {
                    challengerWorkspace++;
                }
            }
            trial.reset();
            try (final var replay = new ReplayPolicy(trial.state(), winner)) {
                try {
                    trial.state().schedule(replay, requests);
                } finally {
                    replaySteps += replay.index;
                }
            }
        } catch (SnapshotLimit limit) {
            incumbentWorkspace++;
        } finally {
            // No trial access is allowed after the first canonical commit changes the source revision.
            trial.reset();
        }
        if (winner == null) {
            fallback.schedule(requests);
            return;
        }
        for (int index = 0; index < requests.length; index++) {
            if (winner[index] != null) {
                context.place(requests[index], winner[index]);
            }
        }
    }

    private Snapshot trajectory(Engine.Trial trial, Request[] requests, long windowSeed,
            boolean portfolio, long heldBytes) throws Exception {
        final var state = trial.state();
        final SchedulingAlgorithm policy = portfolio
                ? new PortfolioFitCount(state, windowSeed, catalogue, 20, 4, 4, cap, cacheRows)
                : new RandomNFitCount(state, windowSeed, catalogue, 20, cap, cacheRows);
        trajectories++;
        try (policy) {
            state.schedule(policy, requests);
            trialDecisions += requests.length;
        } finally {
            trialPlannerCalls = Math.addExact(trialPlannerCalls, policy.plannerCalls());
        }
        return snapshot(state, requests, heldBytes);
    }

    private Snapshot snapshot(Engine state, Request[] requests, long heldBytes) {
        long bytes = Math.addExact(16L, Math.multiplyExact(8L, requests.length));
        reserve(heldBytes, bytes);
        final var result = new Assignment[requests.length][];
        int admitted = 0;
        for (int index = 0; index < requests.length; index++) {
            final var request = requests[index];
            if (state.placement(request.resource(0)) == null) {
                continue;
            }
            bytes = Math.addExact(bytes, Math.addExact(16L, Math.multiplyExact(8L, request.size())));
            reserve(heldBytes, bytes);
            final var plans = new Assignment[request.size()];
            result[index] = plans;
            for (int member = 0; member < request.size(); member++) {
                final var placement = state.placement(request.resource(member));
                final long arrays = Math.addExact((long) placement.diskOffsets().length,
                        Math.addExact((long) placement.devices().length, placement.gpus().length));
                bytes = Math.addExact(bytes, Math.addExact(112L, Math.multiplyExact(4L, arrays)));
                reserve(heldBytes, bytes);
                plans[member] = placement.copy();
            }
            admitted++;
        }
        return new Snapshot(result, bytes, admitted);
    }

    private void reserve(long heldBytes, long bytes) {
        final long total = Math.addExact(heldBytes, bytes);
        if (total > snapshotBudget) {
            throw new SnapshotLimit();
        }
        snapshotPeak = Math.max(snapshotPeak, total);
    }

    static boolean strictSuperset(Assignment[][] incumbent, Assignment[][] challenger) {
        boolean extra = false;
        for (int index = 0; index < incumbent.length; index++) {
            if (incumbent[index] != null && challenger[index] == null) {
                return false;
            }
            extra |= incumbent[index] == null && challenger[index] != null;
        }
        return extra;
    }

    private record Snapshot(Assignment[][] plans, long bytes, int admitted) {
    }

    private static final class SnapshotLimit extends RuntimeException {
    }

    private static final class ReplayPolicy extends SchedulingAlgorithm {
        private final Assignment[][] plans;
        private int index;

        ReplayPolicy(SchedulingContext context, Assignment[][] plans) {
            super(context);
            this.plans = plans;
        }

        @Override
        public void schedule(Request request) {
            final var placement = plans[index++];
            if (placement != null) {
                context.place(request, placement);
            }
        }
    }

    @Override
    public long plannerCalls() {
        return Math.addExact(fallback.plannerCalls(), trialPlannerCalls);
    }

    @Override
    public void capacityChanged(int host) {
        fallback.capacityChanged(host);
    }

    @Override
    public void close() throws Exception {
        fallback.close();
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("window_seed_rule", "mix64(seed^mix64(nonempty_window))",
                "maxWindow", Integer.toString(MAX_WINDOW), "maxTrajectories", "2",
                "comparePortfolio", Boolean.toString(comparePortfolio),
                "snapshotBudgetBytes", Long.toString(snapshotBudget), "incumbent", "random20",
                "challenger", "random20+fragmentation4+least4", "copy_cap", Integer.toString(cap),
                "cache_rows", Integer.toString(cacheRows));
    }

    @Override
    public Map<String, Long> counters() {
        return Map.ofEntries(Map.entry("nonempty_windows", windows), Map.entry("trial_windows", trialWindows),
                Map.entry("trial_trajectories", trajectories), Map.entry("trial_unit_decisions", trialDecisions),
                Map.entry("challenger_skipped_full", skippedFull), Map.entry("challenger_superset_wins", supersetWins),
                Map.entry("challenger_rejected", rejected), Map.entry("prefix_replay_steps", replaySteps),
                Map.entry("snapshot_payload_peak_bytes", snapshotPeak), Map.entry("fallback_oversized_window", oversized),
                Map.entry("fallback_trial_unavailable", trialUnavailable),
                Map.entry("fallback_incumbent_workspace", incumbentWorkspace),
                Map.entry("fallback_challenger_workspace", challengerWorkspace));
    }
}
