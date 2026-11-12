package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.IntStream;

import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

abstract class RlAlgorithm extends SchedulingAlgorithm {
    protected static final Parameter MODEL = Parameter.text("model", "Model",
            "Путь к JSON-модели; пустое значение использует встроенную PPO-политику.", "");

    private final PolicyNetwork network;
    private final FitCount delegate;
    private final int topN;

    protected RlAlgorithm(SchedulingContext context, long seed, Request[] catalogue,
            int topN, int fitCap, int cacheRows, Path model) throws IOException {
        super(context, seed);
        if (topN < 1) {
            throw new IllegalArgumentException("RL shortlist size must be positive");
        }
        this.topN = topN;
        network = PolicyNetwork.load(model);
        final var features = new RlFeatures(context);
        delegate = new FitCount(context, seed, catalogue, fitCap, cacheRows,
                (request, plans, count) -> count <= topN ? IntStream.range(0, count).toArray()
                        : network.shortlist(features.extract(request, plans, count), request.size(), topN));
    }

    protected static Path modelPath(Map<String, JsonPrimitive> parameters) {
        final String value = parameters.get("model").getAsString();
        return value.isBlank() ? null : Path.of(value);
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
    public final void placed(Assignment placement) {
        delegate.placed(placement);
    }

    @Override
    public final void removed(Assignment placement) {
        delegate.removed(placement);
    }

    @Override
    public final Map<String, String> parameters() {
        return Map.of("model_sha256", network.sha256(), "topN", Integer.toString(topN));
    }
}
