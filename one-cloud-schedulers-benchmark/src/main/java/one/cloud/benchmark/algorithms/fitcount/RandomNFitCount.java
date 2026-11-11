package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.SplittableRandom;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class RandomNFitCount extends SchedulingAlgorithm {
    private final FitCount delegate;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("randomn-fitcount", "Random-N + FitCount", AlgorithmParameters.SHORTLIST,
                (context, catalogue, parameters) -> new RandomNFitCount(
                        context,
                        parameters.get("seed").getAsLong(),
                        catalogue,
                        parameters.get("topN").getAsInt(),
                        parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt()));
    }

    public RandomNFitCount(SchedulingContext context, long seed, Request[] catalogue, int n) {
        this(context, seed, catalogue, n, 10, 256);
    }

    public RandomNFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int n, int maxFitCount, int maxCachedShapes) {
        super(context, seed);
        if (n <= 0) {
            throw new IllegalArgumentException("Shortlist size must be positive");
        }
        final var shortlistRandom = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
        delegate = new FitCount(context, seed, catalogue, maxFitCount, maxCachedShapes,
                (request, plans, count) -> {
                    final int[] indices = new int[count];
                    Arrays.setAll(indices, index -> index);
                    if (count <= n) {
                        return indices;
                    }
                    final int[] selected = new int[n];
                    int remaining = count;
                    for (int index = 0; index < n; index++) {
                        final int chosen = shortlistRandom.nextInt(remaining);
                        selected[index] = indices[chosen];
                        indices[chosen] = indices[--remaining];
                    }
                    Arrays.sort(selected);
                    return selected;
                });
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
    public void close() throws Exception {
        delegate.close();
    }
}
