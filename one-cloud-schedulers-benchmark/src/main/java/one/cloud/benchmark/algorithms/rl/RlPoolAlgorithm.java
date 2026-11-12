package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.MaskedFitCount;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.algorithms.AlgorithmParameters.CACHE_ROWS;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.FIT_CAP;
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
    private final SchedulingAlgorithm delegate;
    private final RlPoolSelector selector;

    RlPoolAlgorithm(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras, boolean masked) throws IOException {
        super(context, seed);
        selector = new RlPoolSelector(context, seed, topN, poolSize, extras, model, uniformExtras);
        delegate = masked
                ? new MaskedFitCount(context, seed, catalogue, fitCap, cacheRows, selector, topN + extras)
                : new FitCount(context, seed, catalogue, fitCap, cacheRows, selector);
    }

    @Override
    public final void schedule(Request request) throws Exception {
        delegate.schedule(request);
    }

    @Override
    public final long plannerCalls() {
        return delegate.plannerCalls();
    }

    @Override
    public final void placed(Assignment placement) {
        delegate.placed(placement);
    }

    @Override
    public final void removed(Assignment placement) {
        delegate.removed(placement);
    }

    @Override
    public final Map<String, String> parameters() {
        final var result = new LinkedHashMap<>(delegate.parameters());
        result.putAll(selector.parameters());
        return result;
    }

    @Override
    public final Map<String, Long> counters() {
        final var result = new LinkedHashMap<>(delegate.counters());
        result.putAll(selector.counters());
        return result;
    }

    @Override
    public final void close() throws Exception {
        delegate.close();
    }
}
