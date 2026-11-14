package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
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
    private final int cap, rows;
    private final int[] groupSizes;
    private final long workLimit, memoryLimit;
    private final boolean dp;
    private final long[] reasons = new long[REASONS.length];
    private long decisions, delegations, certified, optimized, trialCalls, operations, transitions, workspacePeak;
    private long orderChecksum = 0xcbf29ce484222325L;

    public static AlgorithmDefinition definition() {
        return AlgorithmDefinition.withCatalogue(
                "bulk-fitcount",
                "Bulk FitCount",
                AlgorithmParameters.BULK_WITH_TOTAL_LIMIT,
                (context, catalogue, parameters) -> new BulkFitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(),
                        parameters.get("maxTotalFit").getAsDouble()));
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
                        parameters.get("cacheRows").getAsInt()));
    }

    public BulkFitCount(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, 128, 256);
    }

    public BulkFitCount(SchedulingContext context, long seed, Request[] catalogue, int cap, int rows) {
        this(context, seed, Catalogue.sampled(catalogue), cap, rows, 0);
    }

    BulkFitCount(SchedulingContext context, long seed, Catalogue catalogue, int cap, int rows, double maxTotalFit) {
        this(context, seed, catalogue, cap, rows, false, WORK_LIMIT, MEMORY_LIMIT, maxTotalFit);
    }

    public static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows) {
        return dynamicProgramming(context, seed, catalogue, cap, rows, WORK_LIMIT, MEMORY_LIMIT);
    }

    static BulkFitCount dynamicProgramming(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int rows, long workLimit, long memoryLimit) {
        return new BulkFitCount(context, seed, Catalogue.sampled(catalogue), cap, rows, true, workLimit, memoryLimit, 0);
    }

    private BulkFitCount(SchedulingContext context, long seed, Catalogue catalogue,
            int cap, int rows, boolean dp, long workLimit, long memoryLimit, double maxTotalFit) {
        super(context);
        new CountDp.Work(workLimit, memoryLimit);
        fallback = new FitCount(context, seed, catalogue, cap, rows, maxTotalFit, null);
        this.cap = cap;
        this.rows = rows;
        this.dp = dp;
        this.workLimit = workLimit;
        this.memoryLimit = memoryLimit;
        if (!dp) {
            groupSizes = new int[0];
            return;
        }
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
        decisions++;
        if (!dp) {
            fallback.scheduleBatch(requests, memoryLimit);
            optimized++;
            return;
        }
        final Engine.Trial trial;
        try {
            trial = context.trial(context.model());
        } catch (UnsupportedOperationException unsupported) {
            reason("trial_unavailable");
            fallback.schedule(requests);
            return;
        }
        final var state = trial.state();
        final var planner = new GreedyPlanner(state);
        final var work = new CountDp.Work(workLimit, memoryLimit);
        Assignment[][] winner = null;
        try {
            work.reserve(16L + 4L * groupSizes.length);
            final var model = new BulkModel(context, requests, groupSizes, work);
            if (context.model().hostCount != 0) {
                model.tree(work);
                certified++;
                final var samples = freeze(work);
                final var curve = new Curve(trial, planner, requests, samples, work);
                final int[] counts = new CountDp(model, requests.length, samples.length, curve, work).solve();
                trial.reset();
                final var replay = new CountsPolicy(state, planner, counts);
                state.schedule(replay, requests);
                if (!replay.complete) {
                    throw new BulkModel.Unsupported("prefix_replay");
                }
                winner = snapshot(state, requests, work);
                optimized++;
            }
        } catch (BulkModel.Unsupported | CountDp.Limit unsupported) {
            reason(unsupported.getMessage());
        } finally {
            trialCalls = Math.addExact(trialCalls, planner.planCalls());
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

    private FitCountCache.Sample[] freeze(CountDp.Work work) {
        final var cache = fallback.cache();
        final int count = cache.samples().length;
        work.reserve(128L + 40L * count);
        work.charge(3L * count);
        final var samples = new ArrayList<FitCountCache.Sample>(count);
        cache.beginOrder();
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            samples.add(sample);
            orderChecksum = (orderChecksum ^ sample.ordinal) * 0x100000001b3L;
            orderChecksum = (orderChecksum ^ sample.total) * 0x100000001b3L;
        }
        return samples.toArray(FitCountCache.Sample[]::new);
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
            trial.state().schedule(new SchedulingAlgorithm(trial.state()) {
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

    private static final class CountsPolicy extends SchedulingAlgorithm {
        private final GreedyPlanner planner;
        private final int[] counts;
        private int host = 1;
        boolean complete = true;

        CountsPolicy(SchedulingContext context, GreedyPlanner planner, int[] counts) {
            super(context);
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

    private static Assignment[] first(GreedyPlanner planner, Request request, int host) {
        for (final int numa : planner.numaNodes(host)) {
            final var plan = planner.plan(request, host, numa);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Assignment[][] snapshot(Engine state, Request[] requests, CountDp.Work work) {
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

    private void reason(String reason) {
        for (int index = 0; index < REASONS.length; index++) {
            if (REASONS[index].equals(reason)) {
                reasons[index]++;
                return;
            }
        }
        throw new IllegalStateException("Unknown bulk fallback: " + reason);
    }

    @Override
    public long plannerCalls() {
        return Math.addExact(fallback.plannerCalls(), trialCalls);
    }

    @Override
    public void capacityChanged(int host) {
        fallback.capacityChanged(host);
    }

    @Override
    public Map<String, String> parameters() {
        final var result = new TreeMap<String, String>(fallback.parameters());
        result.put("profile", dp ? "dp-fitcount-v2" : "bulk-fitcount-v3");
        result.put("copy_cap", Integer.toString(cap));
        result.put("cache_rows", Integer.toString(rows));
        result.put("objective", dp ? "max-admitted-then-frozen-entry-scarcity-lex-physical-copy-loss"
                : "sequential-current-scarcity-lex-physical-copy-loss");
        result.put("workspace_limit_bytes", Long.toString(memoryLimit));
        if (dp) {
            result.put("max_replicas", "100");
            result.put("trajectories_per_host", "1");
            result.put("work_limit", Long.toString(workLimit));
            result.put("workspace_scope", "optimizer arrays, gate/tree, retained geometry; excludes shared trial/planner and fallback/cache");
            result.put("max_tree_depth", "5");
        } else {
            result.put("cache_scope", "one-schedule-batch");
            result.put("workspace_scope", "batch loss rows, retained geometry, anchor slots and physical shape; excludes shared planner and persistent physical cache");
        }
        result.put("workers", "1");
        return result;
    }

    @Override
    public Map<String, Long> counters() {
        final var result = new TreeMap<String, Long>(fallback.counters());
        result.put("bulk_decisions", decisions);
        result.put("single_delegations", delegations);
        result.put("certified_bulks", certified);
        result.put("optimized_bulks", optimized);
        result.putAll(fallback.batchCounters());
        result.put("component_operations", operations);
        result.put("dp_transitions", transitions);
        result.put("optimizer_workspace_peak_bytes", Math.max(workspacePeak, result.get("optimizer_workspace_peak_bytes")));
        result.put("frozen_order_checksum", orderChecksum);
        for (int index = 0; index < reasons.length; index++) {
            result.put("fallback_" + REASONS[index], reasons[index]);
        }
        return result;
    }
}
