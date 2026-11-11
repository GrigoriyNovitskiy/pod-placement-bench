package one.cloud.benchmark.algorithms.catboost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import ai.catboost.CatBoostError;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class CatBoostTop1 extends CatBoostAlgorithm {
    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("catboost-top1", "CatBoost Top-1", List.of(),
                (context, catalogue, parameters) -> new CatBoostTop1(
                        context,
                        44,
                        catalogue,
                        10,
                        64,
                        null));
    }

    public CatBoostTop1(SchedulingContext context, long seed, Request[] catalogue)
            throws IOException, CatBoostError {
        this(context, seed, catalogue, 10, 256, null);
    }

    public CatBoostTop1(SchedulingContext context, long seed, Request[] catalogue,
            int fitCap, int cacheRows, Path modelPath) throws IOException, CatBoostError {
        super(context, seed, catalogue, 1, fitCap, cacheRows, modelPath,
                "/one/cloud/benchmark/algorithms/catboost/fitcount.cbm");
    }
}
