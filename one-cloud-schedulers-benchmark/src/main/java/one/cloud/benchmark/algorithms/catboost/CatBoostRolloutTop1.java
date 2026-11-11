package one.cloud.benchmark.algorithms.catboost;

import java.io.IOException;
import java.nio.file.Path;

import ai.catboost.CatBoostError;

import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class CatBoostRolloutTop1 extends CatBoostAlgorithm {
    public CatBoostRolloutTop1(SchedulingContext context, long seed, Request[] catalogue)
            throws IOException, CatBoostError {
        this(context, seed, catalogue, 10, 256, null);
    }

    public CatBoostRolloutTop1(SchedulingContext context, long seed, Request[] catalogue,
            int fitCap, int cacheRows, Path modelPath) throws IOException, CatBoostError {
        super(context, seed, catalogue, 1, fitCap, cacheRows, modelPath,
                "/one/cloud/benchmark/algorithms/catboost/rollout.cbm");
    }
}
