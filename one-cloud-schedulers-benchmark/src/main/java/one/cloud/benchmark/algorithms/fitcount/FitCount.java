package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.SplittableRandom;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class FitCount extends SchedulingAlgorithm {
    @FunctionalInterface
    public interface CandidateSelector {
        /** Read only the first count plans; return nonempty, strictly increasing indices. */
        int[] select(Request request, Assignment[][] plans, int count) throws Exception;
    }

    private final GreedyPlanner planner;
    private final FitCountCache cache;
    private final int cap;
    private final int[] hosts;
    private final Assignment[][] plans;
    private final long[] weightedLosses;
    private final CandidateSelector selector;
    private final MaskedFitCountOrder maskedOrder;
    private final boolean allMasksAllowed;
    private final String maskDisabledReason;
    private final long maskedWorkspacePeak;
    private final int maxRetainedWidth;
    private Assignment[][] alternativePlans;
    private int[] alternativeHosts;
    private int planCount;
    private long selectorCalls, candidatePlans, candidateHosts, retainedPlans, retainedHosts;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition(
                "fitcount",
                "FitCount",
                AlgorithmParameters.FIT_COUNT,
                (context, catalogue, parameters) -> new FitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt()));
    }

    public static AlgorithmDefinition weightedDefinition() {
        return new AlgorithmDefinition(
                "mean-fitcount",
                "Mean FitCount",
                AlgorithmParameters.FIT_COUNT,
                (context, catalogue, parameters) -> weighted(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt()));
    }

    public FitCount(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, 10, 256);
    }

    public FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes) {
        this(context, seed, catalogue, maxFitCount, maxCachedShapes, false, null);
    }

    public FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, CandidateSelector selector) {
        this(context, seed, catalogue, maxFitCount, maxCachedShapes, false, selector);
    }

    public static FitCount weighted(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes) {
        return new FitCount(context, seed, catalogue, maxFitCount, maxCachedShapes, true, null);
    }

    private FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, boolean weighted, CandidateSelector selector) {
        this(context, seed, catalogue, maxFitCount, maxCachedShapes, weighted, selector, 0);
    }

    FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, CandidateSelector selector, int maxRetainedWidth) {
        this(context, seed, catalogue, maxFitCount, maxCachedShapes, false, selector, maxRetainedWidth);
    }

    private FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, boolean weighted, CandidateSelector selector,
            int maxRetainedWidth) {
        super(context, seed);
        planner = new GreedyPlanner(context);
        final var compiler = maxRetainedWidth == 0 ? null
                : new MaskedFitCountOrder.Compiler(context.model(), maxRetainedWidth);
        cache = new FitCountCache(context, planner, catalogue, maxFitCount, maxCachedShapes, compiler);
        maskedOrder = compiler == null ? null : compiler.finish(cache, maxFitCount);
        allMasksAllowed = compiler != null && compiler.allAllowed();
        cache.attach(allMasksAllowed ? null : maskedOrder);
        maskDisabledReason = compiler == null ? "" : compiler.disabledReason();
        maskedWorkspacePeak = compiler == null ? 0 : compiler.peak();
        this.maxRetainedWidth = maxRetainedWidth;
        cap = maxFitCount;
        this.selector = selector;
        int candidates = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            candidates = Math.addExact(candidates, planner.numaNodes(host).length);
        }
        hosts = new int[candidates];
        plans = new Assignment[hosts.length][];
        weightedLosses = weighted ? new long[candidates] : null;
    }

    @Override
    public void schedule(Request request) {
        final var plan = select(request);
        if (plan != null) {
            context.place(request, plan);
        }
    }

    public Assignment[] select(Request request) {
        try {
            collect(request);
            return winner(request);
        } finally {
            clearPlans();
        }
    }

    public Assignment[][] selectCandidates(Request request, int limit, long seed) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Candidate limit must be positive");
        }
        int feasibleHosts = 0;
        try {
            collect(request);
            if (planCount == 0) {
                return new Assignment[0][];
            }
            if (alternativePlans == null) {
                alternativePlans = new Assignment[context.model().hostCount + 1][];
                alternativeHosts = new int[alternativePlans.length - 1];
            }
            for (int index = 0; index < planCount; index++) {
                final int host = hosts[index];
                if (alternativePlans[host] == null) {
                    alternativePlans[host] = plans[index];
                    alternativeHosts[feasibleHosts++] = host;
                }
            }
            final var permutation = new SplittableRandom(seed);
            for (int index = feasibleHosts - 1; index > 0; index--) {
                final int other = permutation.nextInt(index + 1);
                final int host = alternativeHosts[index];
                alternativeHosts[index] = alternativeHosts[other];
                alternativeHosts[other] = host;
            }
            final var best = winner(request);
            final var result = new Assignment[Math.min(limit, feasibleHosts)][];
            result[0] = best;
            int next = 1;
            for (int index = 0; index < feasibleHosts && next < result.length; index++) {
                final int host = alternativeHosts[index];
                if (host != best[0].host()) {
                    result[next++] = alternativePlans[host];
                }
            }
            return result;
        } finally {
            clearPlans();
            for (int index = 0; index < feasibleHosts; index++) {
                alternativePlans[alternativeHosts[index]] = null;
            }
        }
    }

    FitCountCache cache() {
        return cache;
    }

    private void collect(Request request) {
        for (int host = 1; host <= context.model().hostCount; host++) {
            for (final int numa : planner.numaNodes(host)) {
                final var plan = planner.plan(request, host, numa);
                if (plan != null) {
                    hosts[planCount] = host;
                    plans[planCount++] = plan;
                }
            }
        }
    }

    private Assignment[] winner(Request request) {
        int candidates = planCount;
        if (candidates == 0) {
            return null;
        }
        if (selector != null && candidates > 1) {
            candidates = retainSelected(request, candidates);
        }
        if (candidates > 1) {
            candidates = maskedOrder != null && !allMasksAllowed ? retainMaskedBest(candidates)
                    : weightedLosses == null ? retainLexicographicBest(candidates) : retainWeightedBest(candidates);
        }
        return plans[candidates == 1 ? 0 : random.nextInt(candidates)];
    }

    private void clearPlans() {
        Arrays.fill(plans, 0, planCount, null);
        planCount = 0;
    }

    private int retainSelected(Request request, int candidates) {
        final int[] selected;
        try {
            selected = selector.select(request, plans, candidates);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Candidate selection failed", failure);
        }
        if (selected == null || selected.length == 0 || selected.length > candidates) {
            throw new IllegalArgumentException("Selector must retain at least one feasible candidate");
        }
        if (maxRetainedWidth > 0 && selected.length > maxRetainedWidth) {
            throw new IllegalArgumentException("Selector exceeded its declared maximum retained width");
        }
        int previous = -1;
        for (final int index : selected) {
            if (index <= previous || index >= candidates) {
                throw new IllegalArgumentException("Selector indices must be increasing and within candidate bounds");
            }
            previous = index;
        }
        if (maxRetainedWidth > 0) {
            selectorCalls++;
            candidatePlans += candidates;
            retainedPlans += selected.length;
            int previousHost = 0;
            for (int index = 0; index < candidates; index++) {
                if (hosts[index] != previousHost) {
                    candidateHosts++;
                    previousHost = hosts[index];
                }
            }
            previousHost = 0;
            for (final int index : selected) {
                if (hosts[index] != previousHost) {
                    retainedHosts++;
                    previousHost = hosts[index];
                }
            }
        }
        for (int retained = 0; retained < selected.length; retained++) {
            final int index = selected[retained];
            hosts[retained] = hosts[index];
            plans[retained] = plans[index];
        }
        return selected.length;
    }

    private int retainMaskedBest(int candidates) {
        maskedOrder.beginOrder();
        FitCountCache.Sample lastFamily = null;
        final int[] losses = maskedOrder.losses;
        for (var profile = maskedOrder.next(); profile != null; profile = maskedOrder.next()) {
            if (profile.family != lastFamily) {
                Arrays.fill(losses, 0, candidates, Integer.MIN_VALUE);
                lastFamily = profile.family;
            }
            final int[] counts = cache.counts(profile.family);
            int best = Integer.MAX_VALUE;
            int retained = 0;
            for (int index = 0; index < candidates; index++) {
                final int host = hosts[index];
                int loss = 0;
                if (maskedOrder.allows(profile, host)) {
                    if (losses[index] == Integer.MIN_VALUE) {
                        losses[index] = counts[host] - planner.fitCount(profile.family.request, host, plans[index], cap);
                    }
                    loss = losses[index];
                }
                if (loss < best) {
                    retained = 0;
                    best = loss;
                }
                if (loss == best) {
                    hosts[retained] = host;
                    plans[retained] = plans[index];
                    losses[retained++] = losses[index];
                }
            }
            candidates = retained;
            if (candidates == 1) {
                break;
            }
        }
        return candidates;
    }

    Map<String, String> maskedParameters() {
        return Map.of("masked_enabled", Boolean.toString(maskedOrder != null),
                "masked_disabled_reason", maskDisabledReason,
                "masked_profile_limit", Integer.toString(MaskedFitCountOrder.PROFILE_LIMIT),
                "masked_workspace_limit_bytes", Long.toString(MaskedFitCountOrder.WORKSPACE_LIMIT),
                "masked_max_retained_width", Integer.toString(maxRetainedWidth));
    }

    Map<String, Long> maskedCounters() {
        final var result = new LinkedHashMap<String, Long>();
        if (maskedOrder != null) {
            result.putAll(maskedOrder.counters());
        } else {
            result.putAll(Map.of("logical_profiles", 0L, "physical_families", (long) cache.samples().length,
                    "distinct_masks", 0L, "empty_mask_profiles", 0L, "masked_row_rebuilds", 0L,
                    "masked_host_deltas", 0L, "masked_membership_checks", 0L,
                    "masked_workspace_payload_peak_bytes", maskedWorkspacePeak,
                    "masked_profile_limit", "profile_limit".equals(maskDisabledReason) ? 1L : 0L,
                    "masked_workspace_limit", "workspace_limit".equals(maskDisabledReason) ? 1L : 0L));
            result.put("masked_family_refreshes", 0L);
        }
        result.put("selector_calls", selectorCalls);
        result.put("candidate_plans", candidatePlans);
        result.put("candidate_hosts", candidateHosts);
        result.put("retained_plans", retainedPlans);
        result.put("retained_hosts", retainedHosts);
        return Map.copyOf(result);
    }

    private int retainLexicographicBest(int candidates) {
        cache.beginOrder();
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            int best = Integer.MAX_VALUE;
            int retained = 0;
            for (int index = 0; index < candidates; index++) {
                final int host = hosts[index];
                final var plan = plans[index];
                final int loss = cache.count(sample, host) - planner.fitCount(sample.request, host, plan, cap);
                if (loss < best) {
                    retained = 0;
                    best = loss;
                }
                if (loss == best) {
                    hosts[retained] = host;
                    plans[retained++] = plan;
                }
            }
            candidates = retained;
            if (candidates == 1) {
                break;
            }
        }
        return candidates;
    }

    private int retainWeightedBest(int candidates) {
        Arrays.fill(weightedLosses, 0, candidates, 0L);
        // Visit every candidate while the shape's row is resident, even if the catalogue exceeds the cache.
        for (final var sample : cache.samples()) {
            final int[] counts = cache.counts(sample);
            for (int index = 0; index < candidates; index++) {
                final int host = hosts[index];
                final int loss = counts[host] - planner.fitCount(sample.request, host, plans[index], cap);
                weightedLosses[index] += (long) sample.frequency * loss;
            }
        }
        long best = Long.MAX_VALUE;
        int retained = 0;
        for (int index = 0; index < candidates; index++) {
            final long loss = weightedLosses[index];
            if (loss < best) {
                retained = 0;
                best = loss;
            }
            if (loss == best) {
                plans[retained++] = plans[index];
            }
        }
        return retained;
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }

    @Override
    public void placed(Assignment placement) {
        cache.changed(placement.host());
    }

    @Override
    public void removed(Assignment placement) {
        cache.changed(placement.host());
    }
}
