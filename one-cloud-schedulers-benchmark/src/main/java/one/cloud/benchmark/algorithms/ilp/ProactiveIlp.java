package one.cloud.benchmark.algorithms.ilp;

import java.util.List;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class ProactiveIlp extends SchedulingAlgorithm {
    private static final int MAX_MOVES = CbcDefragmenter.MAX_MOVES;

    private final Engine engine;
    private final GeometricBestFit greedy;
    private final CbcDefragmenter defragmenter;
    private final int every;
    private int admissionsSinceRepair;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("proactive-ilp", "Proactive ILP (CBC)",
                List.of(AlgorithmParameters.SEED,
                        Parameter.integer("every", "Repair interval", "Число успешных заселений между запусками.",
                                100, 1, Integer.MAX_VALUE),
                        Parameter.integer("maxMoves", "Maximum moves", "Максимум переездов за один запуск.",
                                MAX_MOVES, 1, MAX_MOVES),
                        Parameter.integer("timeoutMs", "Repair budget (ms)", "Бюджет одного поиска в миллисекундах.",
                                200, 1, Integer.MAX_VALUE),
                        Parameter.number("probeCpu", "Probe vCPU", "Число vCPU эталонного пода.", 8, .001, 1_000_000),
                        Parameter.integer("probeRamMiB", "Probe RAM (MiB)", "Память эталонного пода в МиБ.",
                                32768, 1, Integer.MAX_VALUE)),
                (context, catalogue, parameters) -> new ProactiveIlp(context, parameters.get("seed").getAsLong(),
                        parameters.get("every").getAsInt(), parameters.get("maxMoves").getAsInt(),
                        parameters.get("timeoutMs").getAsInt(), parameters.get("probeCpu").getAsDouble(),
                        parameters.get("probeRamMiB").getAsInt()));
    }

    private ProactiveIlp(SchedulingContext context, long seed, int every, int maxMoves, int timeoutMs,
            double probeCpu, double probeRam) {
        super(context);
        if (!(context instanceof Engine placementEngine)) {
            throw new IllegalArgumentException("Proactive ILP requires the placement engine");
        }
        engine = placementEngine;
        greedy = new GeometricBestFit(context, seed);
        this.every = every;
        defragmenter = new CbcDefragmenter(engine, maxMoves, timeoutMs, probeCpu, probeRam);
    }

    @Override
    public void schedule(Request request) throws Exception {
        greedy.schedule(request);
        if (engine.placement(request.resource(0)) == null || ++admissionsSinceRepair < every) {
            return;
        }
        admissionsSinceRepair = 0;
        defragmenter.proactive(request);
    }

    @Override
    public long plannerCalls() {
        return greedy.plannerCalls() + defragmenter.plannerCalls();
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("base", "geometric-best-fit", "solver", defragmenter.solverVersion());
    }

    @Override
    public Map<String, Long> counters() {
        return defragmenter.proactiveCounters();
    }
}
