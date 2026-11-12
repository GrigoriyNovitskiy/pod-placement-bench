package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.algorithms.AlgorithmParameters.CACHE_ROWS;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.FIT_CAP;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.SEED;
import static one.cloud.benchmark.algorithms.AlgorithmParameters.TOP_N;

public final class RlTopNFitCount extends RlAlgorithm {
    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("rl-topn-fitcount", "RL Top-N + FitCount",
                List.of(TOP_N, MODEL, SEED, FIT_CAP, CACHE_ROWS),
                (context, catalogue, parameters) -> new RlTopNFitCount(
                        context, parameters.get("seed").getAsLong(), catalogue,
                        parameters.get("topN").getAsInt(), parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), modelPath(parameters)));
    }

    public RlTopNFitCount(SchedulingContext context, long seed, Request[] catalogue,
            int topN, int fitCap, int cacheRows, Path model) throws IOException {
        super(context, seed, catalogue, topN, fitCap, cacheRows, model);
    }
}
