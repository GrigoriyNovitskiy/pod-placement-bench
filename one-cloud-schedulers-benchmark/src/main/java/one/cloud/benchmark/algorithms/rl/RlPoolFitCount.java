package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class RlPoolFitCount extends RlPoolAlgorithm {
    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("rl-pool-fitcount", "RL pool + FitCount", PARAMETERS,
                (context, catalogue, parameters) -> new RlPoolFitCount(
                        context, parameters.get("seed").getAsLong(), catalogue,
                        parameters.get("topN").getAsInt(), parameters.get("poolSize").getAsInt(),
                        parameters.get("extraCandidates").getAsInt(), parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), RlAlgorithm.modelPath(parameters),
                        parameters.get("uniformExtras").getAsBoolean()));
    }

    public RlPoolFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model) throws IOException {
        this(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, false);
    }

    public RlPoolFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras) throws IOException {
        super(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, uniformExtras, false);
    }
}
