package one.cloud.benchmark.algorithms.yp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.ilp.CbcDefragmenter;
import one.cloud.benchmark.engine.Defragmenter;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.ReplayMaintenance;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.Request;

public final class YpPlanner extends SchedulingAlgorithm implements ReplayMaintenance {
    private final Engine engine;
    private final YpLight light;
    private final Defragmenter defragmenter;
    private final int retryEvery, proactiveEvery, retryLimit;
    private final LinkedHashSet<Request> pending = new LinkedHashSet<>();

    private long clock, nextRetry, nextProactive;
    private long queued, cancelled, lateAdmissions, retryAttempts, retriedByLight, maintenanceNanos;
    private boolean retrying;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("yp-planner", "Yandex Planner (Light + CBC)",
                List.of(AlgorithmParameters.SEED,
                        Parameter.integer("retryEvery", "Retry interval", "Операций между проходами очереди.",
                                20, 1, Integer.MAX_VALUE),
                        Parameter.integer("proactiveEvery", "Proactive interval", "Операций между проактивными проходами.",
                                100, 1, Integer.MAX_VALUE),
                        Parameter.integer("retryLimit", "Retry limit", "Максимум запросов на проход очереди.",
                                4, 1, 64),
                        Parameter.integer("maxMoves", "Maximum moves", "Максимум переездов на один поиск.",
                                CbcDefragmenter.MAX_MOVES, 1, CbcDefragmenter.MAX_MOVES),
                        Parameter.integer("timeoutMs", "Search budget (ms)", "Бюджет одного поиска CBC.",
                                200, 1, Integer.MAX_VALUE),
                        Parameter.number("probeCpu", "Probe vCPU", "Число vCPU эталонного пода.",
                                8, .001, 1_000_000),
                        Parameter.integer("probeRamMiB", "Probe RAM (MiB)", "Память эталонного пода в МиБ.",
                                32768, 1, Integer.MAX_VALUE)),
                (context, catalogue, parameters) -> {
                    final var engine = YpLight.requireEngine(context);
                    final var defragmenter = new CbcDefragmenter(engine, parameters.get("maxMoves").getAsInt(),
                            parameters.get("timeoutMs").getAsInt(), parameters.get("probeCpu").getAsDouble(),
                            parameters.get("probeRamMiB").getAsInt());
                    return new YpPlanner(engine, parameters.get("seed").getAsLong(), defragmenter,
                            parameters.get("retryEvery").getAsInt(), parameters.get("proactiveEvery").getAsInt(),
                            parameters.get("retryLimit").getAsInt());
                });
    }

    public YpPlanner(Engine engine, long seed, Defragmenter defragmenter,
            int retryEvery, int proactiveEvery, int retryLimit) {
        super(engine);
        if (retryEvery < 1 || proactiveEvery < 1 || retryLimit < 1) {
            throw new IllegalArgumentException("Maintenance intervals and retry limit must be positive");
        }
        this.engine = engine;
        this.defragmenter = Objects.requireNonNull(defragmenter);
        light = new YpLight(engine, seed);
        this.retryEvery = retryEvery;
        this.proactiveEvery = proactiveEvery;
        this.retryLimit = retryLimit;
        nextRetry = retryEvery;
        nextProactive = proactiveEvery;
    }

    @Override
    public void schedule(Request request) throws Exception {
        light.schedule(request);
        if (engine.placement(request.resource(0)) != null) {
            if (retrying) {
                retriedByLight++;
            }
        } else if (retrying) {
            defragmenter.defragment(Optional.of(request));
        } else if (pending.add(request)) {
            queued++;
        }
    }

    @Override
    public void cancel(Request request) {
        if (pending.remove(request)) {
            cancelled++;
        }
    }

    @Override
    public List<Request> advance(int operations) throws Exception {
        if (operations < 1) {
            throw new IllegalArgumentException("Must advance by at least one operation");
        }
        clock += operations;
        final boolean retry = clock >= nextRetry;
        final boolean proactive = clock >= nextProactive;
        if (retry) {
            nextRetry = (clock / retryEvery + 1) * retryEvery;
        }
        if (proactive) {
            nextProactive = (clock / proactiveEvery + 1) * proactiveEvery;
        }
        if (!proactive && (!retry || pending.isEmpty())) {
            return List.of();
        }

        final long start = System.nanoTime();
        try {
            final var admitted = retry ? retryPending() : List.<Request>of();
            if (proactive) {
                defragmenter.defragment(Optional.empty());
            }
            return admitted;
        } finally {
            maintenanceNanos += System.nanoTime() - start;
        }
    }

    private List<Request> retryPending() throws Exception {
        final int count = Math.min(retryLimit, pending.size());
        final var admitted = new ArrayList<Request>();
        retrying = true;
        try {
            for (int i = 0; i < count; i++) {
                final var request = pending.removeFirst();
                retryAttempts++;
                engine.schedule(this, request);
                if (engine.placement(request.resource(0)) != null) {
                    admitted.add(request);
                    lateAdmissions++;
                } else {
                    pending.add(request);
                }
            }
        } finally {
            retrying = false;
        }
        return admitted;
    }

    @Override
    public long plannerCalls() {
        return light.plannerCalls() + defragmenter.plannerCalls();
    }

    @Override
    public Map<String, String> parameters() {
        final var values = new LinkedHashMap<>(light.parameters());
        values.putAll(defragmenter.parameters());
        return Map.copyOf(values);
    }

    @Override
    public Map<String, Long> counters() {
        final var values = new LinkedHashMap<>(defragmenter.counters());
        values.put("yp_queued", queued);
        values.put("yp_pending", (long) pending.size());
        values.put("yp_cancelled", cancelled);
        values.put("yp_late_admissions", lateAdmissions);
        values.put("yp_retry_attempts", retryAttempts);
        values.put("yp_light_retried", retriedByLight);
        values.put("yp_maintenance_nanos", maintenanceNanos);
        return Map.copyOf(values);
    }

    @Override
    public void close() throws Exception {
        defragmenter.close();
    }
}
