package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.fitcount.Catalogue;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.MaskedFitCount;
import one.cloud.benchmark.algorithms.fitcount.PortfolioFitCount;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.algorithms.AlgorithmParameters.CACHE_ROWS;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.FIT_CAP;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.MAX_TOTAL_FIT;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.SEED;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.TOP_N;

abstract class RlPoolAlgorithm extends SchedulingAlgorithm {
    static final List<Parameter> PARAMETERS = List.of(TOP_N,
            Parameter.integer("poolSize", "Pool size", "Общий предел random и auxiliary кандидатов.", 64, 1, 4096),
            Parameter.integer("extraCandidates", "Extra candidates", "Число дополнительных кандидатов вне random shortlist.",
                    8, 0, 1000),
            RlAlgorithm.MODEL,
            Parameter.bool("uniformExtras", "Uniform extra control", "Выбирать добавки случайно из того же pool.",
                    false).asAdvanced(),
            SEED, FIT_CAP, CACHE_ROWS);
    static final List<Parameter> FIT_COUNT_PARAMETERS;

    static {
        final var parameters = new ArrayList<>(PARAMETERS);
        parameters.add(MAX_TOTAL_FIT);
        FIT_COUNT_PARAMETERS = List.copyOf(parameters);
    }

    enum Refinement {
        LEXICOGRAPHIC, MEAN, MASKED
    }

    private final SchedulingAlgorithm delegate;
    private final RlPoolSelector selector;
    private final boolean geometricPortfolio, bulk;

    RlPoolAlgorithm(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras, Refinement refinement,
            double maxTotalFit) throws IOException {
        this(context, seed, Catalogue.sampled(catalogue), topN, poolSize, extras, fitCap, cacheRows, model,
                uniformExtras, refinement, maxTotalFit);
    }

    RlPoolAlgorithm(SchedulingContext context, long seed, Catalogue catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras, Refinement refinement,
            double maxTotalFit) throws IOException {
        super(context);
        geometricPortfolio = false;
        bulk = false;
        selector = new RlPoolSelector(context, seed, topN, poolSize, extras, model, uniformExtras);
        delegate = switch (refinement) {
            case MASKED -> new MaskedFitCount(context, seed, catalogue.requests(), fitCap, cacheRows, selector, topN + extras);
            case LEXICOGRAPHIC -> new FitCount(context, seed, catalogue, fitCap, cacheRows, maxTotalFit, selector);
            case MEAN -> FitCount.weighted(context, seed, catalogue, fitCap, cacheRows, maxTotalFit, selector);
        };
    }

    RlPoolAlgorithm(SchedulingContext context, long seed, Catalogue catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras, double maxTotalFit,
            int geometricCandidates, int leastCandidates, boolean bulk) throws IOException {
        super(context);
        if (geometricCandidates < 0 || geometricCandidates > 16 || leastCandidates < 0 || leastCandidates > 16
                || (long) topN + extras + geometricCandidates + leastCandidates > 28) {
            throw new IllegalArgumentException("RL geometric portfolio must retain at most 28 candidate plans");
        }
        geometricPortfolio = true;
        this.bulk = bulk;
        selector = new RlPoolSelector(context, seed, topN, poolSize, extras, model, uniformExtras);
        delegate = PortfolioFitCount.geometricLexicographic(context, seed, catalogue, selector, topN,
                geometricCandidates, leastCandidates, fitCap, cacheRows, maxTotalFit, bulk);
    }

    @Override
    public final void schedule(Request request) throws Exception {
        delegate.schedule(request);
    }

    @Override
    public final void schedule(Request[] requests) throws Exception {
        delegate.schedule(requests);
    }

    @Override
    public final long plannerCalls() {
        return delegate.plannerCalls();
    }

    @Override
    public final void capacityChanged(int host) {
        delegate.capacityChanged(host);
    }

    @Override
    public final Map<String, String> parameters() {
        final var result = new LinkedHashMap<>(delegate.parameters());
        result.putAll(selector.parameters());
        if (geometricPortfolio) {
            result.put("profile", bulk ? "rl-pool-geo-least-bulk-fitcount-v1" : "rl-pool-geo-least-fitcount-v1");
            result.put("candidate_budget", "28");
            if (!bulk) {
                result.put("objective", "sequential-geometric-portfolio-scarcity-lex-physical-copy-loss");
            }
        }
        return result;
    }

    @Override
    public final Map<String, Long> counters() {
        final var result = new LinkedHashMap<>(delegate.counters());
        if (geometricPortfolio) {
            selector.counters().forEach((key, value) -> result.put("rl_pool_" + key, value));
        } else {
            result.putAll(selector.counters());
        }
        return result;
    }

    @Override
    public final void close() throws Exception {
        delegate.close();
    }
}
