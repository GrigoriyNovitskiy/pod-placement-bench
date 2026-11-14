package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring.Strategy;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class PortfolioFitCount extends SchedulingAlgorithm {
    private static final long FRAGMENTATION_SALT = 0x3C6EF372FE94F82BL;
    private static final long LEAST_SALT = 0xA54FF53A5F1D36F1L;
    private static final long GEOMETRIC_SALT = 0x510E527FADE682D1L;
    private final FitCount delegate;
    private final CandidateSampler sampler;
    private final ToDoubleFunction<Assignment[]> fragmentation, least, geometric;
    private final TopK fragmented, spread, packed;
    private final int randomCandidates, cap, cacheRows;
    private final long seed;
    private long attempts, attempt, selectorCalls, candidatePlans, candidateHosts, retainedPlans, retainedHosts;
    private long fragmentationScores, leastScores, geometricScores, overlap, cpuRamDecisions;

    public static AlgorithmDefinition definition() {
        return definition("portfolio-fitcount", "Portfolio + FitCount", false, false);
    }

    public static AlgorithmDefinition weightedDefinition() {
        return definition("portfolio-mean-fitcount", "Portfolio + Mean FitCount", true, false);
    }

    public static AlgorithmDefinition geometricWeightedDefinition() {
        return definition("geometric-portfolio-mean-fitcount", "Geometric portfolio + Mean FitCount", true, true);
    }

    private static AlgorithmDefinition definition(String id, String name, boolean weighted, boolean geometric) {
        return AlgorithmDefinition.withCatalogue(id, name,
                List.of(Parameter.integer("randomCandidates", "Random candidates", "Число случайных планов.",
                                20, 1, 64),
                        geometric ? Parameter.integer("geometricCandidates", "Geometric candidates",
                                "Число планов с минимальным normalized residual L2 score.", 4, 0, 16)
                                : Parameter.integer("fragmentationCandidates", "Fragmentation candidates",
                                        "Число планов с минимальной фрагментацией.", 4, 0, 16),
                        Parameter.integer("leastCandidates", "LeastAllocated candidates",
                                "Число планов с максимальным LeastAllocated score.", 4, 0, 16),
                        AlgorithmParameters.SEED, AlgorithmParameters.FIT_CAP, AlgorithmParameters.CACHE_ROWS,
                        AlgorithmParameters.MAX_TOTAL_FIT),
                (context, catalogue, parameters) -> new PortfolioFitCount(context,
                        parameters.get("seed").getAsLong(), catalogue,
                        parameters.get("randomCandidates").getAsInt(),
                        geometric ? 0 : parameters.get("fragmentationCandidates").getAsInt(),
                        parameters.get("leastCandidates").getAsInt(),
                        geometric ? parameters.get("geometricCandidates").getAsInt() : 0,
                        parameters.get("fitCap").getAsInt(), parameters.get("cacheRows").getAsInt(),
                        parameters.get("maxTotalFit").getAsDouble(), weighted));
    }

    public PortfolioFitCount(SchedulingContext context, long seed, Request[] catalogue) {
        this(context, seed, catalogue, 20, 4, 4, 10, 64);
    }

    public PortfolioFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int randomCandidates, int fragmentationCandidates, int leastCandidates, int cap, int cacheRows) {
        this(context, seed, Catalogue.sampled(catalogue), randomCandidates, fragmentationCandidates, leastCandidates, 0,
                cap, cacheRows, 0, false);
    }

    private PortfolioFitCount(SchedulingContext context, long seed, Catalogue catalogue,
            int randomCandidates, int fragmentationCandidates, int leastCandidates, int geometricCandidates,
            int cap, int cacheRows, double maxTotalFit, boolean weighted) {
        super(context);
        if (randomCandidates < 1 || randomCandidates > 64 || fragmentationCandidates < 0
                || fragmentationCandidates > 16 || leastCandidates < 0 || leastCandidates > 16
                || geometricCandidates < 0 || geometricCandidates > 16) {
            throw new IllegalArgumentException("Portfolio quotas are outside their supported ranges");
        }
        this.seed = seed;
        this.randomCandidates = randomCandidates;
        this.cap = cap;
        this.cacheRows = cacheRows;
        sampler = new CandidateSampler(seed);
        fragmented = new TopK(fragmentationCandidates);
        spread = new TopK(leastCandidates);
        packed = new TopK(geometricCandidates);
        final var planner = fragmentationCandidates != 0 || leastCandidates != 0 || geometricCandidates != 0
                ? new GreedyPlanner(context) : null;
        fragmentation = fragmentationCandidates == 0 ? null : context.model().gpus == null
                ? planner.cpuRamFragmentationScorer(catalogue.requests()) : planner.gpuFragmentationScorer(catalogue.requests());
        least = leastCandidates == 0 ? null : KubernetesResourceScoring.scorer(planner, Strategy.LEAST_ALLOCATED);
        geometric = geometricCandidates == 0 ? null : planner.normalizedResidualL2Scorer();
        delegate = weighted ? FitCount.weighted(context, seed, catalogue, cap, cacheRows, maxTotalFit, this::select)
                : new FitCount(context, seed, catalogue, cap, cacheRows, maxTotalFit, this::select);
    }

    @Override
    public void schedule(Request request) {
        attempt = attempts++;
        delegate.schedule(request);
    }

    int[] select(Request request, Assignment[][] plans, int count) {
        selectorCalls++;
        candidatePlans += count;
        candidateHosts += hosts(plans, null, count);
        final var random = sampler.sample(count, randomCandidates);
        if (count <= randomCandidates) {
            retainedPlans += count;
            retainedHosts += hosts(plans, random, random.length);
            return random;
        }
        fragmented.clear();
        spread.clear();
        packed.clear();
        final long decision = seed ^ mix64(attempt);
        for (int index = 0; index < count; index++) {
            if (fragmentation != null) {
                fragmented.offer(index, fragmentation.applyAsDouble(plans[index]),
                        mix64(decision ^ mix64(index) ^ FRAGMENTATION_SALT));
                fragmentationScores++;
            }
            if (least != null) {
                spread.offer(index, -least.applyAsDouble(plans[index]),
                        mix64(decision ^ mix64(index) ^ LEAST_SALT));
                leastScores++;
            }
            if (geometric != null) {
                packed.offer(index, geometric.applyAsDouble(plans[index]),
                        mix64(decision ^ mix64(index) ^ GEOMETRIC_SALT));
                geometricScores++;
            }
        }
        if (fragmentation != null && context.model().gpus == null) {
            cpuRamDecisions++;
        }
        final int[] union = Arrays.copyOf(random, random.length + fragmented.size + spread.size + packed.size);
        fragmented.append(union, random.length);
        spread.append(union, random.length + fragmented.size);
        packed.append(union, random.length + fragmented.size + spread.size);
        Arrays.sort(union);
        int retained = 0;
        for (final int index : union) {
            if (retained == 0 || union[retained - 1] != index) {
                union[retained++] = index;
            }
        }
        overlap += union.length - retained;
        retainedPlans += retained;
        retainedHosts += hosts(plans, union, retained);
        return retained == union.length ? union : Arrays.copyOf(union, retained);
    }

    private static int hosts(Assignment[][] plans, int[] indices, int count) {
        int previous = 0;
        int hosts = 0;
        for (int index = 0; index < count; index++) {
            final int host = plans[indices == null ? index : indices[index]][0].host();
            if (host != previous) {
                hosts++;
                previous = host;
            }
        }
        return hosts;
    }

    private static final class TopK {
        private final int[] indices;
        private final double[] scores;
        private final long[] keys;
        private int size;

        TopK(int count) {
            indices = new int[count];
            scores = new double[count];
            keys = new long[count];
        }

        void clear() {
            size = 0;
        }

        void offer(int index, double score, long key) {
            if (!Double.isFinite(score)) {
                throw new IllegalStateException("Portfolio scorer returned a non-finite score");
            }
            int position = 0;
            while (position < size && compare(score, key, index, position) >= 0) {
                position++;
            }
            if (position == indices.length) {
                return;
            }
            final int end = Math.min(size, indices.length - 1);
            for (int cursor = end; cursor > position; cursor--) {
                indices[cursor] = indices[cursor - 1];
                scores[cursor] = scores[cursor - 1];
                keys[cursor] = keys[cursor - 1];
            }
            indices[position] = index;
            scores[position] = score;
            keys[position] = key;
            size = Math.min(indices.length, size + 1);
        }

        private int compare(double score, long key, int index, int position) {
            if (score < scores[position]) {
                return -1;
            }
            if (score > scores[position]) {
                return 1;
            }
            final int comparison = Long.compareUnsigned(key, keys[position]);
            return comparison == 0 ? Integer.compare(index, indices[position]) : comparison;
        }

        void append(int[] target, int offset) {
            System.arraycopy(indices, 0, target, offset, size);
        }
    }

    static long mix64(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    @Override
    public long plannerCalls() {
        return delegate.plannerCalls();
    }

    @Override
    public void capacityChanged(int host) {
        delegate.capacityChanged(host);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    @Override
    public Map<String, String> parameters() {
        final var result = new LinkedHashMap<>(delegate.parameters());
        result.putAll(Map.of("random_candidates", Integer.toString(randomCandidates),
                "fragmentation_candidates", Integer.toString(fragmented.indices.length),
                "least_candidates", Integer.toString(spread.indices.length),
                "geometric_candidates", Integer.toString(packed.indices.length),
                "fragmentation_source", context.model().gpus == null ? "cpu_ram" : "gpu_devices",
                "copy_cap", Integer.toString(cap), "cache_rows", Integer.toString(cacheRows)));
        return result;
    }

    @Override
    public Map<String, Long> counters() {
        final var result = new LinkedHashMap<>(delegate.counters());
        result.putAll(Map.ofEntries(Map.entry("selector_calls", selectorCalls), Map.entry("candidate_plans", candidatePlans),
                Map.entry("candidate_hosts", candidateHosts), Map.entry("retained_plans", retainedPlans),
                Map.entry("retained_hosts", retainedHosts), Map.entry("fragmentation_scores", fragmentationScores),
                Map.entry("least_scores", leastScores), Map.entry("geometric_scores", geometricScores),
                Map.entry("shortlist_overlap", overlap),
                Map.entry("fragmentation_cpu_ram_decisions", cpuRamDecisions)));
        return result;
    }
}
