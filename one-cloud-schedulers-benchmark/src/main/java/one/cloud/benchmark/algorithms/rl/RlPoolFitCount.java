package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.fitcount.Catalogue;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class RlPoolFitCount extends RlPoolAlgorithm {
    private static final List<Parameter> GEOMETRIC_PARAMETERS = geometricParameters();

    public static AlgorithmDefinition definition() {
        return definition("rl-pool-fitcount", "RL pool + FitCount", false);
    }

    public static AlgorithmDefinition weightedDefinition() {
        return definition("rl-pool-mean-fitcount", "RL pool + Mean FitCount", true);
    }

    public static AlgorithmDefinition geometricDefinition() {
        return geometricDefinition(false);
    }

    public static AlgorithmDefinition geometricBulkDefinition() {
        return geometricDefinition(true);
    }

    private static List<Parameter> geometricParameters() {
        final var parameters = new ArrayList<>(FIT_COUNT_PARAMETERS);
        parameters.set(0, Parameter.integer("topN", "Random candidates", "Число базовых случайных планов.", 12, 1, 28));
        parameters.add(3, Parameter.integer("geometricCandidates", "Geometric candidates",
                "Число планов с минимальным normalized residual L2 score.", 4, 0, 16));
        parameters.add(4, Parameter.integer("leastCandidates", "LeastAllocated candidates",
                "Число планов с максимальным LeastAllocated score.", 4, 0, 16));
        return List.copyOf(parameters);
    }

    private static AlgorithmDefinition geometricDefinition(boolean bulk) {
        return AlgorithmDefinition.withCatalogue(
                bulk ? "rl-pool-geo-least-bulk-fitcount" : "rl-pool-geo-least-fitcount",
                bulk ? "RL pool + Geometric + LeastAllocated + Bulk FitCount"
                        : "RL pool + Geometric + LeastAllocated + FitCount",
                GEOMETRIC_PARAMETERS,
                (context, catalogue, parameters) -> new RlPoolFitCount(context,
                        parameters.get("seed").getAsLong(), catalogue,
                        parameters.get("topN").getAsInt(), parameters.get("poolSize").getAsInt(),
                        parameters.get("extraCandidates").getAsInt(), parameters.get("fitCap").getAsInt(),
                        parameters.get("cacheRows").getAsInt(), RlAlgorithm.modelPath(parameters),
                        parameters.get("uniformExtras").getAsBoolean(), parameters.get("maxTotalFit").getAsDouble(),
                        parameters.get("geometricCandidates").getAsInt(), parameters.get("leastCandidates").getAsInt(), bulk));
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
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras, double maxTotalFit,
            int geometricCandidates, int leastCandidates, boolean bulk) throws IOException {
        super(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, uniformExtras,
                maxTotalFit, geometricCandidates, leastCandidates, bulk);
    }

    private RlPoolFitCount(SchedulingContext context, long seed, Catalogue catalogue, int topN, int poolSize,
            int extras, int fitCap, int cacheRows, Path model, boolean uniformExtras,
            double maxTotalFit, boolean weighted) throws IOException {
        super(context, seed, catalogue, topN, poolSize, extras, fitCap, cacheRows, model, uniformExtras,
                weighted ? Refinement.MEAN : Refinement.LEXICOGRAPHIC, maxTotalFit);
    }
}
