package one.cloud.benchmark.algorithms.catboost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import ai.catboost.CatBoostError;

import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public abstract class CatBoostAlgorithm extends SchedulingAlgorithm {
    private final FitCount delegate;
    private final CatBoostScorer scorer;
    private final CandidateFeatures features;
    private final int topN;

    protected CatBoostAlgorithm(SchedulingContext context, long seed, Request[] catalogue,
            int topN, int fitCap, int cacheRows, Path modelPath, String modelResource)
            throws IOException, CatBoostError {
        super(context);
        if (topN <= 0) {
            throw new IllegalArgumentException("Candidate shortlist size must be positive");
        }
        this.topN = topN;
        features = new CandidateFeatures(context);
        scorer = new CatBoostScorer(modelPath, modelResource);
        try {
            delegate = new FitCount(context, seed, catalogue, fitCap, cacheRows, this::select);
        } catch (RuntimeException | Error failure) {
            try {
                scorer.close();
            } catch (CatBoostError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private int[] select(Request request, Assignment[][] plans, int count) throws CatBoostError {
        if (count <= topN) {
            return java.util.stream.IntStream.range(0, count).toArray();
        }
        final var scores = new double[count];
        final var indices = new Integer[count];
        final var decision = features.prepare(request, plans, count);
        for (int first = 0; first < decision.rows();) {
            final int rows = Math.min(CandidateFeatures.Decision.MAX_BATCH_ROWS, decision.rows() - first);
            final var memberScores = scorer.score(decision.extract(first, rows));
            for (int row = 0; row < rows; row++) {
                scores[(first + row) / request.size()] += memberScores[row];
            }
            first += rows;
        }
        for (int candidate = 0; candidate < count; candidate++) {
            indices[candidate] = candidate;
        }
        Arrays.sort(indices, (left, right) -> {
            final int comparison = Double.compare(scores[right], scores[left]);
            return comparison == 0 ? Integer.compare(left, right) : comparison;
        });
        final int[] selected = new int[topN];
        for (int index = 0; index < selected.length; index++) {
            selected[index] = indices[index];
        }
        Arrays.sort(selected);
        return selected;
    }

    public final String modelSha256() {
        return scorer.sha256();
    }

    public final int topN() {
        return topN;
    }

    @Override
    public final void schedule(Request request) {
        delegate.schedule(request);
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
    public final void close() throws CatBoostError {
        scorer.close();
    }
}
