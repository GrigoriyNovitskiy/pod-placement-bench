package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class BulkFitCount extends SchedulingAlgorithm {
    private static final long WORK_LIMIT = 100_000_000, MEMORY_LIMIT = 64L << 20;
    private static final String[] REASONS = {"trial_unavailable", "replica_limit", "member_stencil", "member_kind",
            "service_identity", "group_stencil", "identity_dependent_group", "replica_shape", "relative_links",
            "multi_host_unit", "inter_unit_dependency", "dependencies_not_ready", "crossing_domains", "tree_depth",
            "work_budget", "workspace_budget", "prefix_replay"};
    private final FitCount fallback;
    private final Request[] catalogue;
    private final int cap, rows;
    private final int[] groupSizes;
    private final long seed, workLimit, memoryLimit;
    private final boolean dp, incumbentGuard;
    private final long[] reasons = new long[REASONS.length];
    private long decisions, delegations, certified, wins, retained, trialCalls, operations, transitions, workspacePeak;
    private long incumbentSearches, unguarded, marginalEvaluations, marginalCacheHits;
    private long orderChecksum = 0xcbf29ce484222325L;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition(
                "bulk-fitcount",
                "Bulk FitCount",
                AlgorithmParameters.BULK,
                (context, catalogue, parameters) -> new BulkFitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(),
                        parameters.get("incumbentGuard").getAsBoolean()));
    }

    public static AlgorithmDefinition dynamicProgrammingDefinition() {
        return new AlgorithmDefinition(
                "dp",
                "DP + FitCount",
                AlgorithmParameters.BULK,
                (context, catalogue, parameters) -> dynamicProgramming(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(),
                        parameters.get("incumbentGuard").getAsBoolean()));
    }

    public BulkFitCount(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, 128, 256);
    }

    public BulkFitCount(SchedulingContext context, long seed, Request[] catalogue, int cap, int rows) {
        this(context, seed, catalogue, cap, rows, true);
    }

    public BulkFitCount(SchedulingContext context, long seed, Request[] catalogue, int cap, int rows,
            boolean incumbentGuard) {
        this(context, seed, catalogue, cap, rows, false, incumbentGuard, WORK_LIMIT, MEMORY_LIMIT);
    }

    public static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows) {
        return dynamicProgramming(context, seed, catalogue, cap, rows, true);
    }

    public static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows, boolean incumbentGuard) {
        return new BulkFitCount(context, seed, catalogue, cap, rows, true, incumbentGuard, WORK_LIMIT, MEMORY_LIMIT);
    }

    static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows, long workLimit, long memoryLimit) {
        return dynamicProgramming(context, seed, catalogue, cap, rows, true, workLimit, memoryLimit);
    }

    static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows, boolean incumbentGuard, long workLimit, long memoryLimit) {
        return new BulkFitCount(context, seed, catalogue, cap, rows, true, incumbentGuard, workLimit, memoryLimit);
    }

    private BulkFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows, boolean dp, boolean incumbentGuard, long workLimit, long memoryLimit) {
        super(context, seed);
        new CountDp.Work(workLimit, memoryLimit);
        fallback = new FitCount(context, seed, catalogue, cap, rows);
        this.catalogue = catalogue.clone();
        this.cap = cap;
        this.rows = rows;
        this.dp = dp;
        this.incumbentGuard = incumbentGuard;
        this.seed = seed;
        this.workLimit = workLimit;
        this.memoryLimit = memoryLimit;
        final var model = context.model();
        int largest = 0;
        for (final int group : model.rules.group) {
            largest = Math.max(largest, group);
        }
        for (int link = 0; link < model.rules.linkTarget.length; link++) {
            if (model.rules.linkKind[link] != 0) {
                largest = Math.max(largest, model.rules.linkTarget[link]);
            }
        }
        groupSizes = new int[largest + 1];
        for (int resource = 1; resource < model.alloc.length; resource++) {
            final int profile = model.constraints[resource];
            final int kind = model.rules.kind[profile];
            if (kind < 1 || kind > 3) {
                groupSizes[model.rules.group[profile]]++;
            }
        }
    }

    @Override
    public void schedule(Request request) {
        delegations++;
        fallback.schedule(request);
    }

    @Override
    public void schedule(Request[] requests) throws Exception {
        if (requests.length <= 1) {
            if (requests.length != 0) {
                schedule(requests[0]);
            }
            return;
        }
        final long decision = decisions++;
        final Engine.Trial trial;
        try {
            trial = context.trial(context.model());
        } catch (UnsupportedOperationException unsupported) {
            reason("trial_unavailable");
            fallback.schedule(requests);
            return;
        }
        final long incumbentSeed = mix64(seed ^ mix64(decision));
        final var state = trial.state();
        final var baseline = incumbentGuard ? new FitCount(state, incumbentSeed, catalogue, cap, rows) : null;
        final var planner = new GreedyPlanner(state);
        final var work = new CountDp.Work(workLimit, memoryLimit);
        Assignment[][] winner = null;
        try {
            if (baseline != null) {
                incumbentSearches = Math.addExact(incumbentSearches, requests.length);
                state.schedule(baseline, requests);
                winner = snapshot(state, requests, null);
                trial.reset();
            }
            final var incumbent = winner;
            try {
                work.reserve(16L + 4L * groupSizes.length);
                final var model = new BulkModel(context, requests, groupSizes, work);
                if (context.model().hostCount != 0) {
                    if (dp) {
                        model.tree(work);
                    }
                    certified++;
                    final var frozen = freeze(work);
                    if (dp) {
                        final var curve = new Curve(trial, planner, requests, frozen.samples, work);
                        final int[] counts = new CountDp(model, requests.length, frozen.samples.length, curve, work).solve();
                        trial.reset();
                        final var replay = new CountsPolicy(state, planner, counts);
                        state.schedule(replay, requests);
                        if (!replay.complete) {
                            throw new BulkModel.Unsupported("prefix_replay");
                        }
                    } else {
                        state.schedule(new GreedyPolicy(state, planner, requests, frozen.samples,
                                mix64(incumbentSeed ^ 0xd1b54a32d192ed03L), work), requests);
                    }
                    final var proposal = snapshot(state, requests, work);
                    if (incumbent == null) {
                        winner = proposal;
                        unguarded++;
                    } else {
                        final int admitted = admitted(proposal), previous = admitted(incumbent);
                        boolean better = admitted > previous;
                        if (admitted == previous) {
                            final long[] proposedLoss = loss(planner, frozen, work);
                            trial.reset();
                            final var replay = new ReplayPolicy(state, incumbent);
                            state.schedule(replay, requests);
                            if (!replay.complete) {
                                throw new IllegalStateException("Saved incumbent failed prefix replay");
                            }
                            final long[] incumbentLoss = loss(planner, frozen, work);
                            better = compare(proposedLoss, incumbentLoss, work) < 0;
                        }
                        if (better) {
                            winner = proposal;
                            wins++;
                        } else {
                            retained++;
                        }
                    }
                } else if (incumbent != null) {
                    retained++;
                }
            } catch (BulkModel.Unsupported | CountDp.Limit unsupported) {
                reason(unsupported.getMessage());
            }
        } finally {
            trialCalls = Math.addExact(trialCalls, (baseline == null ? 0 : baseline.plannerCalls()) + planner.planCalls());
            operations = Math.addExact(operations, work.operations);
            transitions = Math.addExact(transitions, work.transitions);
            workspacePeak = Math.max(workspacePeak, work.workspace);
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

    private record Frozen(FitCountCache.Sample[] samples, long[] totals) {
    }

    private Frozen freeze(CountDp.Work work) {
        final var cache = fallback.cache();
        final int count = cache.samples().length;
        work.reserve(160L + 48L * count);
        work.charge(4L * count);
        final var samples = new ArrayList<FitCountCache.Sample>(count);
        final long[] totals = new long[count];
        cache.beginOrder();
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            totals[samples.size()] = sample.total;
            samples.add(sample);
            orderChecksum = (orderChecksum ^ sample.ordinal) * 0x100000001b3L;
            orderChecksum = (orderChecksum ^ sample.total) * 0x100000001b3L;
        }
        return new Frozen(samples.toArray(FitCountCache.Sample[]::new), totals);
    }

    private long[] loss(GreedyPlanner planner, Frozen frozen, CountDp.Work work) {
        work.charge(frozen.samples.length);
        final long[] result = new long[frozen.samples.length];
        for (int sample = 0; sample < result.length; sample++) {
            long after = 0;
            for (int host = 1; host <= context.model().hostCount; host++) {
                work.charge(1);
                after = Math.addExact(after, planner.fitCount(frozen.samples[sample].request, host, cap));
            }
            work.charge(2);
            result[sample] = Math.subtractExact(frozen.totals[sample], after);
        }
        return result;
    }

    private final class Curve implements CountDp.Options {
        private final Engine.Trial trial;
        private final GreedyPlanner planner;
        private final Request[] requests;
        private final FitCountCache.Sample[] samples;
        private final CountDp.Work work;
        private final long[] losses;
        private final int[] before;

        Curve(Engine.Trial trial, GreedyPlanner planner, Request[] requests,
                FitCountCache.Sample[] samples, CountDp.Work work) {
            this.trial = trial;
            this.planner = planner;
            this.requests = requests;
            this.samples = samples;
            this.work = work;
            final long cells = (long) (requests.length + 1) * samples.length;
            work.reserve(48L + 8 * cells + 4L * samples.length);
            work.charge(cells + samples.length);
            if (cells > Integer.MAX_VALUE - 8) {
                throw new CountDp.Limit("workspace_budget");
            }
            losses = new long[(int) cells];
            before = new int[samples.length];
        }

        @Override
        public int load(int host) throws Exception {
            trial.reset();
            for (int sample = 0; sample < samples.length; sample++) {
                work.charge(1);
                before[sample] = planner.fitCount(samples[sample].request, host, cap);
            }
            final int[] count = {0};
            trial.state().schedule(new SchedulingAlgorithm(trial.state(), 0) {
                @Override
                public void schedule(Request request) {
                    final var plan = first(planner, request, host);
                    if (plan == null) {
                        return;
                    }
                    context.place(request, plan);
                    final int copies = ++count[0];
                    for (int sample = 0; sample < samples.length; sample++) {
                        work.charge(2);
                        losses[copies * samples.length + sample] = (long) before[sample]
                                - planner.fitCount(samples[sample].request, host, cap);
                    }
                }

                @Override
                public void schedule(Request[] pending) {
                    for (final var request : pending) {
                        final int previous = count[0];
                        schedule(request);
                        if (count[0] == previous) {
                            break;
                        }
                    }
                }
            }, requests);
            return count[0];
        }

        @Override
        public long cost(int copies, int component) {
            return losses[copies * samples.length + component];
        }
    }

    private final class GreedyPolicy extends SchedulingAlgorithm {
        private final GreedyPlanner planner;
        private final Request[] requests;
        private final FitCountCache.Sample[] samples;
        private final CountDp.Work work;
        private final Assignment[][] next;
        private final int[] source, candidates;
        private final int[][] marginals;
        private int index;

        GreedyPolicy(SchedulingContext context, GreedyPlanner planner, Request[] requests,
                FitCountCache.Sample[] samples, long seed, CountDp.Work work) {
            super(context, seed);
            this.planner = planner;
            this.requests = requests;
            this.samples = samples;
            this.work = work;
            final int hosts = context.model().hostCount;
            work.reserve(80L + 16L * (hosts + 1) + 8L * Math.min(rows, samples.length));
            marginals = new int[Math.min(rows, samples.length)][];
            next = new Assignment[hosts + 1][];
            source = new int[hosts + 1];
            candidates = new int[hosts];
        }

        @Override
        public void schedule(Request request) {
            if (index == 0) {
                for (int host = 1; host < next.length; host++) {
                    final var plan = first(planner, request, host);
                    if (plan != null) {
                        work.reserve(2 * bytes(plan));
                        next[host] = plan;
                    }
                }
            }
            int count = 0;
            for (int host = 1; host < next.length; host++) {
                if (next[host] == null) {
                    continue;
                }
                final var plan = source[host] == index ? next[host]
                        : rebind(next[host], requests[source[host]], request);
                if (context.canPlace(request, plan)) {
                    next[host] = plan;
                    source[host] = index;
                    candidates[count++] = host;
                } else {
                    next[host] = null;
                }
            }
            for (int sample = 0; sample < samples.length && count > 1; sample++) {
                int retained = 0;
                int best = Integer.MAX_VALUE;
                for (int candidate = 0; candidate < count; candidate++) {
                    work.charge(3);
                    final int host = candidates[candidate];
                    final int delta = marginal(sample, host);
                    if (delta < best) {
                        best = delta;
                        retained = 0;
                    }
                    if (delta == best) {
                        candidates[retained++] = host;
                    }
                }
                count = retained;
            }
            if (count != 0) {
                final int host = candidates[count == 1 ? 0 : random.nextInt(count)];
                context.place(request, next[host]);
                work.charge(marginals.length);
                for (final var row : marginals) {
                    if (row != null) {
                        row[host] = Integer.MIN_VALUE;
                    }
                }
                if (index + 1 < requests.length) {
                    final var plan = first(planner, requests[index + 1], host);
                    if (plan != null && bytes(plan) > bytes(next[host])) {
                        work.reserve(2 * (bytes(plan) - bytes(next[host])));
                    }
                    next[host] = plan;
                    source[host] = index + 1;
                }
            }
            index++;
        }

        private int marginal(int sample, int host) {
            int[] cached = null;
            if (sample < marginals.length) {
                cached = marginals[sample];
                if (cached == null) {
                    work.reserve(16L + 4L * next.length);
                    work.charge(next.length);
                    cached = new int[next.length];
                    Arrays.fill(cached, Integer.MIN_VALUE);
                    marginals[sample] = cached;
                }
                if (cached[host] != Integer.MIN_VALUE) {
                    marginalCacheHits++;
                    return cached[host];
                }
            }
            marginalEvaluations++;
            final int delta = planner.fitCount(samples[sample].request, host, cap)
                    - planner.fitCount(samples[sample].request, host, next[host], cap);
            if (cached != null) {
                cached[host] = delta;
            }
            return delta;
        }
    }

    private static final class CountsPolicy extends SchedulingAlgorithm {
        private final GreedyPlanner planner;
        private final int[] counts;
        private int host = 1;
        boolean complete = true;

        CountsPolicy(SchedulingContext context, GreedyPlanner planner, int[] counts) {
            super(context, 0);
            this.planner = planner;
            this.counts = counts;
        }

        @Override
        public void schedule(Request request) {
            while (host < counts.length && counts[host] == 0) {
                host++;
            }
            if (host == counts.length || !complete) {
                return;
            }
            final var plan = first(planner, request, host);
            if (plan == null) {
                complete = false;
                return;
            }
            context.place(request, plan);
            counts[host]--;
        }
    }

    private static final class ReplayPolicy extends SchedulingAlgorithm {
        private final Assignment[][] plans;
        private int index;
        boolean complete = true;

        ReplayPolicy(SchedulingContext context, Assignment[][] plans) {
            super(context, 0);
            this.plans = plans;
        }

        @Override
        public void schedule(Request request) {
            final var plan = plans[index++];
            if (plan != null && complete) {
                if (context.canPlace(request, plan)) {
                    context.place(request, plan);
                } else {
                    complete = false;
                }
            }
        }
    }

    private static Assignment[] first(GreedyPlanner planner, Request request, int host) {
        for (final int numa : planner.numaNodes(host)) {
            final var plan = planner.plan(request, host, numa);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Assignment[] rebind(Assignment[] plans, Request source, Request target) {
        final var result = new Assignment[plans.length];
        for (int index = 0; index < plans.length; index++) {
            final var plan = plans[index];
            int member = 0;
            while (source.resource(member) != plan.resource()) {
                member++;
            }
            result[index] = new Assignment(target.resource(member), plan.host(), plan.numa(),
                    plan.diskOffsets(), plan.devices(), plan.gpus());
        }
        return result;
    }

    private static Assignment[][] snapshot(Engine state, Request[] requests, CountDp.Work work) {
        if (work != null) {
            long bytes = 16L + 8L * requests.length;
            for (final var request : requests) {
                if (state.placement(request.resource(0)) != null) {
                    bytes += 16L + 8L * request.size();
                    for (int member = 0; member < request.size(); member++) {
                        final var plan = state.placement(request.resource(member));
                        bytes += 112L + 4L * (plan.diskOffsets().length + plan.devices().length + plan.gpus().length);
                    }
                }
            }
            work.reserve(bytes);
        }
        final var result = new Assignment[requests.length][];
        for (int index = 0; index < requests.length; index++) {
            final var request = requests[index];
            if (state.placement(request.resource(0)) != null) {
                result[index] = new Assignment[request.size()];
                for (int member = 0; member < request.size(); member++) {
                    result[index][member] = state.placement(request.resource(member));
                }
            }
        }
        return result;
    }

    private static long bytes(Assignment[] plans) {
        long bytes = 16L + 8L * plans.length;
        for (final var plan : plans) {
            bytes += 112L + 4L * (plan.diskOffsets().length + plan.devices().length + plan.gpus().length);
        }
        return bytes;
    }

    private static int admitted(Assignment[][] plans) {
        int count = 0;
        for (final var plan : plans) {
            count += plan == null ? 0 : 1;
        }
        return count;
    }

    private static int compare(long[] a, long[] b, CountDp.Work work) {
        for (int index = 0; index < a.length; index++) {
            work.charge(1);
            final int comparison = Long.compare(a[index], b[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private void reason(String reason) {
        for (int index = 0; index < REASONS.length; index++) {
            if (REASONS[index].equals(reason)) {
                reasons[index]++;
                return;
            }
        }
        throw new IllegalStateException("Unknown bulk fallback: " + reason);
    }

    private static long mix64(long z) {
        z = (z ^ z >>> 30) * 0xbf58476d1ce4e5b9L;
        z = (z ^ z >>> 27) * 0x94d049bb133111ebL;
        return z ^ z >>> 31;
    }

    @Override
    public long plannerCalls() {
        return Math.addExact(fallback.plannerCalls(), trialCalls);
    }

    @Override
    public void placed(Assignment placement) {
        fallback.placed(placement);
    }

    @Override
    public void removed(Assignment placement) {
        fallback.removed(placement);
    }

    @Override
    public Map<String, String> parameters() {
        final var result = new TreeMap<String, String>();
        result.put("profile", dp ? "dp-fitcount-v1" : "bulk-fitcount-v1");
        result.put("copy_cap", Integer.toString(cap));
        result.put("cache_rows", Integer.toString(rows));
        result.put("max_replicas", "100");
        result.put("trajectories_per_host", "1");
        result.put("objective", "max-admitted-then-frozen-entry-scarcity-lex-physical-copy-loss");
        result.put("incumbent_seed", "mix64(runSeed ^ mix64(zero-based-bulk-decision))");
        result.put("incumbent_guard", incumbentGuard ? "enabled; incumbent wins ties" : "disabled");
        result.put("work_limit", Long.toString(workLimit));
        result.put("workspace_limit_bytes", Long.toString(memoryLimit));
        result.put("workspace_scope", "optimizer arrays, gate/tree, retained geometry; excludes shared trial/planner and incumbent/cache");
        result.put("max_tree_depth", "5");
        result.put("workers", "1");
        return result;
    }

    @Override
    public Map<String, Long> counters() {
        final var result = new TreeMap<String, Long>();
        result.put("bulk_decisions", decisions);
        result.put("single_delegations", delegations);
        result.put("certified_bulks", certified);
        result.put("optimizer_wins", wins);
        result.put("incumbent_retained", retained);
        result.put("incumbent_searches", incumbentSearches);
        result.put("unguarded_bulks", unguarded);
        result.put("marginal_evaluations", marginalEvaluations);
        result.put("marginal_cache_hits", marginalCacheHits);
        result.put("component_operations", operations);
        result.put("dp_transitions", transitions);
        result.put("optimizer_workspace_peak_bytes", workspacePeak);
        result.put("frozen_order_checksum", orderChecksum);
        for (int index = 0; index < reasons.length; index++) {
            result.put("fallback_" + REASONS[index], reasons[index]);
        }
        return result;
    }
}
