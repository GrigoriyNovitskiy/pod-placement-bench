package one.cloud.benchmark.algorithms.fitcount;

import java.util.List;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class RandomNFitCount extends SchedulingAlgorithm {
    private final FitCount delegate;

    public static AlgorithmDefinition definition() {
        return definition("randomn-fitcount", "Random-N + FitCount",
                AlgorithmParameters.SHORTLIST_WITH_TOTAL_LIMIT, false);
    }

    public static AlgorithmDefinition weightedDefinition() {
        return definition("randomn-mean-fitcount", "Random-N + Mean FitCount", List.of(
                Parameter.integer("topN", "Shortlist size", "Сколько кандидатов передать на окончательный выбор FitCount.",
                        28, 1, 1000),
                AlgorithmParameters.SEED, AlgorithmParameters.FIT_CAP, AlgorithmParameters.CACHE_ROWS,
                AlgorithmParameters.MAX_TOTAL_FIT), true);
    }

    private static AlgorithmDefinition definition(String id, String name, List<Parameter> schema, boolean weighted) {
        return AlgorithmDefinition.withCatalogue(id, name, schema,
                (context, catalogue, parameters) -> new RandomNFitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("topN").getAsInt(),
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), parameters.get("maxTotalFit").getAsDouble(), weighted));
    }

    public RandomNFitCount(SchedulingContext context, long seed, Request[] catalogue, int n) {
        this(context, seed, catalogue, n, 10, 256);
    }

    public RandomNFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int n, int maxFitCount, int maxCachedShapes) {
        this(context, seed, Catalogue.sampled(catalogue), n, maxFitCount, maxCachedShapes, 0, false);
    }

    private RandomNFitCount(SchedulingContext context, long seed, Catalogue catalogue,
            int n, int maxFitCount, int maxCachedShapes, double maxTotalFit, boolean weighted) {
        super(context);
        if (n <= 0) {
            throw new IllegalArgumentException("Shortlist size must be positive");
        }
        final var sampler = new CandidateSampler(seed);
        final FitCount.CandidateSelector selector = (request, plans, count) -> sampler.sample(count, n);
        delegate = weighted ? FitCount.weighted(context, seed, catalogue, maxFitCount, maxCachedShapes, maxTotalFit, selector)
                : new FitCount(context, seed, catalogue, maxFitCount, maxCachedShapes, maxTotalFit, selector);
    }

    @Override
    public void schedule(Request request) {
        delegate.schedule(request);
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
    public Map<String, String> parameters() {
        return delegate.parameters();
    }

    @Override
    public Map<String, Long> counters() {
        return delegate.counters();
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
