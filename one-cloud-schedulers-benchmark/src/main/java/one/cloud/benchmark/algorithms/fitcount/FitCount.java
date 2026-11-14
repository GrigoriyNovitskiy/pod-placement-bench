package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

public final class FitCount extends SchedulingAlgorithm {
    private static final Assignment[] NO_PHYSICAL_PLAN = new Assignment[0];
    @FunctionalInterface
    public interface CandidateSelector {
        /** Read only the first count plans; return nonempty, strictly increasing indices. */
        int[] select(Request request, Assignment[][] plans, int count) throws Exception;
    }

    private final SplittableRandom random;
    private final GreedyPlanner planner;
    private final FitCountCache cache;
    private final int cap, cacheRows;
    private final double maxTotalFit, totalCpu, totalRam;
    private double totalFitLimit = Double.MAX_VALUE;
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
    private long fitCountSamples, fitCountAfterProbes;
    private LinkedHashMap<FitCountCache.Sample, int[]> batchLosses;
    private int[] batchSlots, batchHostOffsets;
    private Assignment[][] batchGeometry;
    private List<ToDoubleFunction<Assignment[]>> batchScorers = List.of();
    private double[][] batchScores;
    private int batchRowLimit;
    private long batchBaseBytes, batchRowBytes, batchGeometryBytes, batchGeometryLimit, batchScoreBytes;
    private long marginalEvaluations, marginalCacheHits, geometryCacheHits, batchWorkspacePeak;
    private long rawScoreEvaluations, rawScoreCacheHits;

    public static AlgorithmDefinition definition() {
        return AlgorithmDefinition.withCatalogue(
                "fitcount",
                "FitCount",
                AlgorithmParameters.FIT_COUNT_WITH_TOTAL_LIMIT,
                (context, catalogue, parameters) -> new FitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(),
                        parameters.get("maxTotalFit").getAsDouble(), null));
    }

    public static AlgorithmDefinition weightedDefinition() {
        return AlgorithmDefinition.withCatalogue(
                "mean-fitcount",
                "Mean FitCount",
                AlgorithmParameters.FIT_COUNT_WITH_TOTAL_LIMIT,
                (context, catalogue, parameters) -> weighted(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(),
                        parameters.get("maxTotalFit").getAsDouble(), null));
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

    public FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, double maxTotalFit, CandidateSelector selector) {
        this(context, seed, Catalogue.sampled(catalogue), maxFitCount, maxCachedShapes, maxTotalFit, selector);
    }

    public FitCount(SchedulingContext context, long seed, Catalogue catalogue,
            int maxFitCount, int maxCachedShapes, double maxTotalFit, CandidateSelector selector) {
        this(context, seed, catalogue, maxFitCount, maxCachedShapes, false, selector, 0, maxTotalFit);
    }

    public static FitCount weighted(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes) {
        return new FitCount(context, seed, catalogue, maxFitCount, maxCachedShapes, true, null);
    }

    public static FitCount weighted(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, double maxTotalFit, CandidateSelector selector) {
        return weighted(context, seed, Catalogue.sampled(catalogue), maxFitCount, maxCachedShapes, maxTotalFit, selector);
    }

    public static FitCount weighted(SchedulingContext context, long seed, Catalogue catalogue,
            int maxFitCount, int maxCachedShapes, double maxTotalFit, CandidateSelector selector) {
        return new FitCount(context, seed, catalogue, maxFitCount, maxCachedShapes, true, selector, 0, maxTotalFit);
    }

    private FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, boolean weighted, CandidateSelector selector) {
        this(context, seed, Catalogue.sampled(catalogue), maxFitCount, maxCachedShapes, weighted, selector, 0, 0);
    }

    FitCount(SchedulingContext context, long seed, Request[] catalogue,
            int maxFitCount, int maxCachedShapes, CandidateSelector selector, int maxRetainedWidth) {
        this(context, seed, Catalogue.sampled(catalogue), maxFitCount, maxCachedShapes, false, selector, maxRetainedWidth, 0);
    }

    private FitCount(SchedulingContext context, long seed, Catalogue catalogue,
            int maxFitCount, int maxCachedShapes, boolean weighted, CandidateSelector selector,
            int maxRetainedWidth, double maxTotalFit) {
        super(context);
        random = new SplittableRandom(seed);
        if (!Double.isFinite(maxTotalFit) || maxTotalFit < 0) {
            throw new IllegalArgumentException("Total FitCount limit must be finite and nonnegative");
        }
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
        cacheRows = maxCachedShapes;
        this.selector = selector;
        this.maxTotalFit = maxTotalFit;
        int candidates = 0;
        double cpu = 0, ram = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            candidates = Math.addExact(candidates, planner.numaNodes(host).length);
            if (maxTotalFit > 0) {
                cpu += supply(host, VCORES_VALUE);
                ram += supply(host, MEM_MIB_VALUE);
            }
        }
        totalCpu = cpu;
        totalRam = ram;
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

    void scheduleBatch(Request[] requests, long memoryLimit) {
        scheduleBatch(requests, memoryLimit, this::schedule);
    }

    void scheduleBatch(Request[] requests, long memoryLimit, Consumer<Request> scheduler) {
        scheduleBatch(requests, memoryLimit, scheduler, List.of());
    }

    /** Registered scorers stay fixed for the batch and depend only on physical geometry and host capacity. */
    void scheduleBatch(Request[] requests, long memoryLimit, Consumer<Request> scheduler,
            List<ToDoubleFunction<Assignment[]>> scorers) {
        if (batchLosses != null) {
            throw new IllegalStateException("A FitCount batch is already active");
        }
        final long baseBytes = 192L + 4L * hosts.length + 4L * (context.model().hostCount + 2);
        if (memoryLimit < baseBytes) {
            for (final var request : requests) {
                scheduler.accept(request);
            }
            return;
        }
        batchSlots = new int[hosts.length];
        batchHostOffsets = new int[context.model().hostCount + 2];
        int slot = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            batchHostOffsets[host] = slot;
            slot += planner.numaNodes(host).length;
        }
        batchHostOffsets[context.model().hostCount + 1] = slot;
        batchLosses = new LinkedHashMap<>(16, .75f, true);
        batchScorers = scorers;
        batchBaseBytes = baseBytes;
        batchRowBytes = 96L + 4L * hosts.length;
        batchWorkspacePeak = Math.max(batchWorkspacePeak, baseBytes);
        Catalogue.Key batchShape = null;
        try {
            for (final var request : requests) {
                final var shape = Catalogue.key(context.model(), request);
                if (!shape.equals(batchShape)) {
                    batchLosses.clear();
                    clearBatchScores();
                    batchGeometry = null;
                    batchGeometryBytes = 0;
                    batchGeometryLimit = 0;
                    batchRowLimit = 0;
                    batchShape = null;
                    batchBaseBytes = baseBytes;
                    final long bytes = baseBytes + 64L + 8L * shape.values().length;
                    if (bytes <= memoryLimit) {
                        batchShape = shape;
                        batchBaseBytes = bytes;
                        batchRowLimit = (int) Math.min(Math.min(cacheRows, cache.samples().length),
                                (memoryLimit - bytes) / batchRowBytes);
                        // Reserve all loss rows before admitting optional geometry.
                        final long remaining = memoryLimit - bytes - batchRowLimit * batchRowBytes;
                        final long indexBytes = 16L + 8L * hosts.length;
                        if (indexBytes <= remaining) {
                            batchGeometry = new Assignment[hosts.length][];
                            batchBaseBytes += indexBytes;
                            batchGeometryLimit = remaining - indexBytes;
                        }
                    }
                    recordBatchWorkspace();
                }
                scheduler.accept(request);
            }
        } finally {
            batchLosses = null;
            batchSlots = null;
            batchHostOffsets = null;
            clearBatchScores();
            batchScorers = List.of();
            batchGeometry = null;
            batchGeometryBytes = 0;
            batchGeometryLimit = 0;
            batchRowLimit = 0;
        }
    }

    Map<String, Long> batchCounters() {
        return Map.of("marginal_evaluations", marginalEvaluations, "marginal_cache_hits", marginalCacheHits,
                "geometry_cache_hits", geometryCacheHits, "raw_score_evaluations", rawScoreEvaluations,
                "raw_score_cache_hits", rawScoreCacheHits, "optimizer_workspace_peak_bytes", batchWorkspacePeak);
    }

    double scoreAt(int candidateIndex, Assignment[] plan, ToDoubleFunction<Assignment[]> scorer) {
        double[] scores = null;
        int slot = -1;
        if (batchGeometry != null && candidateIndex >= 0 && candidateIndex < planCount
                && plans[candidateIndex] == plan) {
            scores = batchScoreRow(scorer);
            if (scores != null) {
                slot = batchSlots[candidateIndex];
                final double cached = scores[slot];
                if (!Double.isNaN(cached)) {
                    rawScoreCacheHits++;
                    return cached;
                }
            }
        }
        rawScoreEvaluations++;
        final double score = scorer.applyAsDouble(plan);
        if (scores != null) {
            scores[slot] = score;
        }
        return score;
    }

    private double[] batchScoreRow(ToDoubleFunction<Assignment[]> scorer) {
        for (int index = 0; index < batchScorers.size(); index++) {
            if (batchScorers.get(index) != scorer) {
                continue;
            }
            if (batchScores != null && batchScores[index] != null) {
                return batchScores[index];
            }
            final long indexBytes = batchScores == null ? 16L + 8L * batchScorers.size() : 0;
            final long rowBytes = 16L + 8L * hosts.length;
            if (indexBytes + rowBytes > batchGeometryLimit - batchGeometryBytes - batchScoreBytes) {
                return null;
            }
            if (batchScores == null) {
                batchScores = new double[batchScorers.size()][];
                batchScoreBytes += indexBytes;
            }
            final var scores = new double[hosts.length];
            Arrays.fill(scores, Double.NaN);
            batchScores[index] = scores;
            batchScoreBytes += rowBytes;
            recordBatchWorkspace();
            return scores;
        }
        return null;
    }

    private void clearBatchScores() {
        batchScores = null;
        batchScoreBytes = 0;
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
        double freeCpu = 0, freeRam = 0;
        int slot = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            if (maxTotalFit > 0) {
                freeCpu += context.available(host, VCORES_VALUE);
                if (totalRam > 0) {
                    freeRam += context.available(host, MEM_MIB_VALUE);
                }
            }
            for (final int numa : planner.numaNodes(host)) {
                final var plan = candidatePlan(request, host, numa, slot);
                if (plan != null) {
                    hosts[planCount] = host;
                    plans[planCount] = plan;
                    if (batchSlots != null) {
                        batchSlots[planCount] = slot;
                    }
                    planCount++;
                }
                slot++;
            }
        }
        if (maxTotalFit > 0) {
            final double usedCpu = Math.max(0, totalCpu - freeCpu);
            final double usedRam = Math.max(0, totalRam - freeRam);
            double growth = Double.MAX_VALUE;
            if (usedCpu > 0) {
                growth = Math.min(growth, totalCpu / usedCpu);
            }
            if (usedRam > 0) {
                growth = Math.min(growth, totalRam / usedRam);
            }
            totalFitLimit = Math.max(maxTotalFit, growth);
        }
    }

    private Assignment[] candidatePlan(Request request, int host, int numa, int slot) {
        if (batchGeometry == null) {
            return planner.plan(request, host, numa);
        }
        final var model = context.model();
        for (int member = 0; member < request.size(); member++) {
            final int profile = model.constraints[request.resource(member)];
            if (!model.candidates.allows(model.rules.candidates[profile], host)
                    || model.rules.pinnedHost[profile] != 0 && model.rules.pinnedHost[profile] != host) {
                return null;
            }
        }
        final var template = batchGeometry[slot];
        if (template != null) {
            geometryCacheHits++;
            if (template == NO_PHYSICAL_PLAN) {
                return null;
            }
            final var rebound = new Assignment[template.length];
            // Physical geometry is stable; eligibility must use the current member IDs and state.
            for (int member = 0; member < template.length; member++) {
                final var plan = template[member];
                rebound[member] = new Assignment(request.resource(member), host, plan.numa(),
                        plan.diskOffsets(), plan.devices(), plan.gpus());
            }
            return context.canPlace(request, rebound) ? rebound : null;
        }
        final var plan = planner.physicalPlan(request, host, numa);
        final var physical = plan == null ? NO_PHYSICAL_PLAN : plan;
        final long bytes = geometryBytes(physical);
        if (bytes <= batchGeometryLimit - batchGeometryBytes) {
            // Optional scores must not prevent geometry from using its original budget.
            if (bytes > batchGeometryLimit - batchGeometryBytes - batchScoreBytes) {
                clearBatchScores();
            }
            batchGeometry[slot] = physical;
            batchGeometryBytes += bytes;
            recordBatchWorkspace();
        }
        return plan != null && context.canPlace(request, plan) ? plan : null;
    }

    private static long geometryBytes(Assignment[] plans) {
        long bytes = 16L + 8L * plans.length;
        for (final var plan : plans) {
            bytes += 112L + 4L * (plan.diskOffsets().length + plan.devices().length + plan.gpus().length);
        }
        return bytes;
    }

    private void recordBatchWorkspace() {
        batchWorkspacePeak = Math.max(batchWorkspacePeak,
                batchBaseBytes + batchGeometryBytes + batchScoreBytes + batchRowBytes * batchLosses.size());
    }

    private double supply(int resource, int dimension) {
        final var model = context.model();
        final int alloc = model.alloc[resource];
        for (int index = model.allocations.offset[alloc]; index < model.allocations.offset[alloc + 1]; index++) {
            if (model.allocations.type[index] == dimension) {
                return model.allocations.amount[index];
            }
        }
        return 0;
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
            if (batchSlots != null) {
                batchSlots[retained] = batchSlots[index];
            }
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
        cache.beginOrder(totalFitLimit);
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            fitCountSamples++;
            int best = Integer.MAX_VALUE;
            int retained = 0;
            final int[] losses = batchRow(sample);
            for (int index = 0; index < candidates; index++) {
                final int host = hosts[index];
                final var plan = plans[index];
                final int loss = loss(sample, index, losses, null);
                if (loss < best) {
                    retained = 0;
                    best = loss;
                }
                if (loss == best) {
                    hosts[retained] = host;
                    plans[retained] = plan;
                    if (batchSlots != null) {
                        batchSlots[retained] = batchSlots[index];
                    }
                    retained++;
                }
            }
            candidates = retained;
            if (candidates == 1) {
                break;
            }
        }
        return candidates;
    }

    private int[] batchRow(FitCountCache.Sample sample) {
        if (batchLosses == null || batchRowLimit == 0) {
            return null;
        }
        var losses = batchLosses.get(sample);
        if (losses == null) {
            if (batchLosses.size() == batchRowLimit) {
                final var iterator = batchLosses.entrySet().iterator();
                losses = iterator.next().getValue();
                iterator.remove();
            } else {
                losses = new int[hosts.length];
            }
            Arrays.fill(losses, Integer.MIN_VALUE);
            batchLosses.put(sample, losses);
            recordBatchWorkspace();
        }
        return losses;
    }

    private int loss(FitCountCache.Sample sample, int index, int[] losses, int[] counts) {
        if (losses != null) {
            final int cached = losses[batchSlots[index]];
            if (cached != Integer.MIN_VALUE) {
                marginalCacheHits++;
                return cached;
            }
        }
        final int host = hosts[index];
        fitCountAfterProbes++;
        final int before = counts == null ? cache.count(sample, host) : counts[host];
        final int loss = before - planner.fitCount(sample.request, host, plans[index], cap);
        if (batchLosses != null) {
            marginalEvaluations++;
            if (losses != null) {
                losses[batchSlots[index]] = loss;
            }
        }
        return loss;
    }

    private int retainWeightedBest(int candidates) {
        Arrays.fill(weightedLosses, 0, candidates, 0L);
        if (maxTotalFit == 0) {
            for (final var sample : cache.samples()) {
                addWeightedLosses(sample, candidates);
            }
        } else {
            cache.beginOrder(totalFitLimit);
            for (var sample = cache.next(); sample != null; sample = cache.next()) {
                addWeightedLosses(sample, candidates);
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

    private void addWeightedLosses(FitCountCache.Sample sample, int candidates) {
        fitCountSamples++;
        final int[] losses = batchRow(sample);
        int[] counts = null;
        for (int index = 0; index < candidates; index++) {
            if (counts == null && (losses == null || losses[batchSlots[index]] == Integer.MIN_VALUE)) {
                counts = cache.counts(sample);
            }
            final int loss = loss(sample, index, losses, counts);
            weightedLosses[index] += (long) sample.frequency * loss;
        }
    }

    @Override
    public Map<String, String> parameters() {
        final var result = new LinkedHashMap<>(cache.parameters());
        result.put("max_total_fit", Double.toString(maxTotalFit));
        result.put("total_fit_limit_mode", maxTotalFit == 0 ? "disabled" : "production_growth_floor");
        return Map.copyOf(result);
    }

    @Override
    public Map<String, Long> counters() {
        return Map.of("fitcount_samples", fitCountSamples, "fitcount_after_probes", fitCountAfterProbes);
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }

    @Override
    public void capacityChanged(int host) {
        cache.changed(host);
        if (batchLosses != null) {
            final int start = batchHostOffsets[host], end = batchHostOffsets[host + 1];
            for (final var losses : batchLosses.values()) {
                Arrays.fill(losses, start, end, Integer.MIN_VALUE);
            }
            if (batchScores != null) {
                for (final var scores : batchScores) {
                    if (scores != null) {
                        Arrays.fill(scores, start, end, Double.NaN);
                    }
                }
            }
            if (batchGeometry != null) {
                for (int slot = start; slot < end; slot++) {
                    if (batchGeometry[slot] != null) {
                        batchGeometryBytes -= geometryBytes(batchGeometry[slot]);
                        batchGeometry[slot] = null;
                    }
                }
            }
        }
    }
}
