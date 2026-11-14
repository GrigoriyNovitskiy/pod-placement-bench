package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.fitcount.Catalogue;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class RlPoolFitCount extends RlPoolAlgorithm {
    public static AlgorithmDefinition definition() {
        return definition("rl-pool-fitcount", "RL pool + FitCount", false);
    }

    public static AlgorithmDefinition weightedDefinition() {
        return definition("rl-pool-mean-fitcount", "RL pool + Mean FitCount", true);
    }

    private static AlgorithmDefinition definition(String id, String name, boolean weighted) {
        return AlgorithmDefinition.withCatalogue(id, name, FIT_COUNT_PARAMETERS,
                (context, catalogue, parameters) -> new RlPoolFitCount(
                        context, parameters.get("seed").getAsLong(), catalogue,
                        parameters.get("topN").getAsInt(), parameters.get("poolSize").getAsInt(),
                        parameters.get("extraCandidates").getAsInt(), parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), RlAlgorithm.modelPath(parameters),
                        parameters.get("uniformExtras").getAsBoolean(),
                        parameters.get("maxTotalFit").getAsDouble(), weighted));
    }

    public RlPoolFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model) throws IOException {
        this(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, false);
    }

    public RlPoolFitCount(SchedulingContext context, long seed, Request[] catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras) throws IOException {
        this(context, seed, Catalogue.sampled(catalogue), topN, poolSize, extras, fitCap, cacheRows, model, uniformExtras, 0, false);
    }

    private RlPoolFitCount(SchedulingContext context, long seed, Catalogue catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras,
            double maxTotalFit, boolean weighted) throws IOException {
        super(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, uniformExtras,
                weighted ? Refinement.MEAN : Refinement.LEXICOGRAPHIC, maxTotalFit);
    }
}
