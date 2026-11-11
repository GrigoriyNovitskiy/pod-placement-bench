package one.cloud.benchmark.algorithms.catboost;

import java.io.IOException;
import java.nio.file.Path;

import ai.catboost.CatBoostError;

import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class CatBoostRolloutTopNFitCount extends CatBoostAlgorithm {
    public CatBoostRolloutTopNFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN)
            throws IOException, CatBoostError {
        this(context, seed, catalogue, topN, 10, 256, null);
    }

    public CatBoostRolloutTopNFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN,
            int fitCap, int cacheRows, Path modelPath) throws IOException, CatBoostError {
        super(context, seed, catalogue, topN, fitCap, cacheRows, modelPath,
                "/one/cloud/benchmark/algorithms/catboost/rollout.cbm");
    }
}
