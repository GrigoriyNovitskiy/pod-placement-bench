package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class RlTop1 extends RlAlgorithm {
    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("rl-top1", "RL Top-1", List.of(MODEL),
                (context, catalogue, parameters) -> new RlTop1(context, modelPath(parameters)));
    }

    public RlTop1(SchedulingContext context, Path model) throws IOException {
        super(context, 0, new Request[0], 1, 1, 1, model);
    }
}
