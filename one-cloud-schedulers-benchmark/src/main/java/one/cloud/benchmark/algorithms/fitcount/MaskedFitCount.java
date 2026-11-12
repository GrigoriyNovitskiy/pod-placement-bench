package one.cloud.benchmark.algorithms.fitcount;

import java.util.Map;
import java.util.Objects;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class MaskedFitCount extends SchedulingAlgorithm {
    private final FitCount delegate;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("masked-fitcount", "Masked FitCount", AlgorithmParameters.SHORTLIST,
                (context, catalogue, parameters) -> new MaskedFitCount(context,
                        parameters.get("seed").getAsLong(), catalogue, parameters.get("topN").getAsInt(),
                        parameters.get("fitCap").getAsInt(), parameters.get("cacheRows").getAsInt()));
    }

    public MaskedFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN) {
        this(context, seed, catalogue, topN, 10, 64);
    }

    public MaskedFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int topN, int cap, int cacheRows) {
        this(context, seed, catalogue, cap, cacheRows, randomSelector(seed, topN), topN);
    }

    public MaskedFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int cap, int cacheRows, FitCount.CandidateSelector selector, int maxRetainedWidth) {
        super(context, seed);
        if (maxRetainedWidth <= 0) {
            throw new IllegalArgumentException("Maximum retained width must be positive");
        }
        delegate = new FitCount(context, seed, catalogue, cap, cacheRows,
                Objects.requireNonNull(selector), maxRetainedWidth);
    }

    private static FitCount.CandidateSelector randomSelector(long seed, int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("Shortlist size must be positive");
        }
        final var sampler = new CandidateSampler(seed);
        return (request, plans, count) -> sampler.sample(count, topN);
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
    public void placed(Assignment placement) {
        delegate.placed(placement);
    }

    @Override
    public void removed(Assignment placement) {
        delegate.removed(placement);
    }

    @Override
    public Map<String, String> parameters() {
        return delegate.maskedParameters();
    }

    @Override
    public Map<String, Long> counters() {
        return delegate.maskedCounters();
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
