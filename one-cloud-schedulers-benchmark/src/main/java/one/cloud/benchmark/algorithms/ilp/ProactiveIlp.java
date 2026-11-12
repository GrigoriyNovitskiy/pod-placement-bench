package one.cloud.benchmark.algorithms.ilp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.CPU;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.RAM;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.SOURCE_LIMIT;

public final class ProactiveIlp extends SchedulingAlgorithm {
    private static final int MAX_MOVES = 2;
    private static final int SOLVE_LIMIT_PER_SOURCE = 3;

    private final Engine engine;
    private final GeometricBestFit greedy;
    private final RelocationCandidates candidates;
    private final String solverVersion;
    private final int every, maxMoves, timeoutMs;
    private final double probeCpu, probeRam;
    private int admissionsSinceRepair;
    private long runs, solves, applied, movedUnits, probeGain, rejectedPlans, repairNanos, solveNanos, plannerCalls;

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
        super(context, seed);
        if (!(context instanceof Engine placementEngine)) {
            throw new IllegalArgumentException("Proactive ILP requires the placement engine");
        }
        engine = placementEngine;
        greedy = new GeometricBestFit(context, seed);
        candidates = new RelocationCandidates(engine);
        this.every = every;
        this.maxMoves = maxMoves;
        this.timeoutMs = timeoutMs;
        this.probeCpu = probeCpu;
        this.probeRam = probeRam;
        try {
            Loader.loadNativeLibraries();
            final var solver = cbc();
            try {
                solverVersion = solver.solverVersion();
            } finally {
                solver.delete();
            }
        } catch (LinkageError | RuntimeException failure) {
            throw new IllegalStateException("Cannot initialize CBC through OR-Tools: " + failure.getMessage(), failure);
        }
    }

    @Override
    public void schedule(Request request) throws Exception {
        greedy.schedule(request);
        if (engine.placement(request.resource(0)) == null || ++admissionsSinceRepair < every) {
            return;
        }
        admissionsSinceRepair = 0;
        runs++;
        final long started = System.nanoTime();
        try {
            if (engine.model().dimensionCount >= RAM) {
                repair(request, started + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            }
        } finally {
            repairNanos += System.nanoTime() - started;
        }
    }

    private void repair(Request admitted, long deadline) throws Exception {
        final var hosts = candidates.rankedHosts();
        final var victimsByHost = candidates.victims(null, admitted);
        int sources = 0;
        for (final int source : hosts) {
            if (sources == SOURCE_LIMIT || deadline - System.nanoTime() <= 0) {
                return;
            }
            final var victims = victimsByHost.get(source);
            if (victims != null) {
                sources++;
                if (trySource(source, victims, hosts, deadline)) {
                    return;
                }
            }
        }
    }

    private boolean trySource(int source, List<Request> victims, List<Integer> hosts, long deadline) throws Exception {
        final var preview = engine.relocationPreview(victims);
        if (preview == null) {
            return false;
        }
        final var planner = new GreedyPlanner(preview);
        final var plans = new ArrayList<List<Assignment[]>>();
        final Set<Integer> modeledHosts = new LinkedHashSet<>();
        modeledHosts.add(source);
        preview.schedule(new SchedulingAlgorithm(preview, 0) {
            @Override
            public void schedule(Request victim) {
                if (deadline - System.nanoTime() <= 0) {
                    return;
                }
                final var options = new ArrayList<Assignment[]>();
                options.add(new Assignment[]{engine.placement(victim.resource(0))});
                for (final var destination : candidates.destinations(victim, source, hosts, planner)) {
                    options.add(destination);
                    modeledHosts.add(destination[0].host());
                }
                plans.add(options);
            }
        }, victims.toArray(Request[]::new));
        plannerCalls += planner.planCalls();
        return plans.size() == victims.size() && modeledHosts.size() > 1 && deadline - System.nanoTime() > 0
                && solve(preview, planner, victims, plans, modeledHosts, deadline);
    }

    private boolean solve(Engine preview, GreedyPlanner planner, List<Request> victims,
            List<List<Assignment[]>> plans, Set<Integer> hosts, long deadline) throws Exception {
        final long baseline = copies(engine, planner, hosts);
        final var solver = cbc();
        try {
            solver.setNumThreads(1);
            final var objective = solver.objective();
            objective.setMaximization();
            final var moveLimit = solver.makeConstraint(0, maxMoves);
            final var improvement = solver.makeConstraint(baseline + 1.0, MPSolver.infinity());
            final var capacity = new LinkedHashMap<Integer, CapacityRows>();
            for (final int host : hosts) {
                final var hostRows = rows(solver, preview, host);
                capacity.put(host, hostRows);
                final var nodes = planner.numaNodes(host);
                for (final int numa : nodes) {
                    if (numa == 0 && nodes.length > 1) {
                        continue;
                    }
                    final int resource = numa == 0 ? host : numa;
                    final var copies = solver.makeIntVar(0, copies(preview, resource), "");
                    hostRows.add(copies, probeCpu, probeRam);
                    if (numa != 0) {
                        final var numaRows = rows(solver, preview, numa);
                        capacity.put(numa, numaRows);
                        numaRows.add(copies, probeCpu, probeRam);
                    }
                    objective.setCoefficient(copies, maxMoves + 1);
                    improvement.setCoefficient(copies, 1);
                }
            }

            final var choices = new ArrayList<Choice>();
            for (int group = 0; group < plans.size(); group++) {
                final var exactlyOne = solver.makeConstraint(1, 1);
                final var options = plans.get(group);
                for (int option = 0; option < options.size(); option++) {
                    final var placement = options.get(option);
                    final var variable = solver.makeBoolVar("");
                    exactlyOne.setCoefficient(variable, 1);
                    Engine.Relocation move = null;
                    if (option != 0) {
                        move = new Engine.Relocation(victims.get(group), placement);
                        moveLimit.setCoefficient(variable, 1);
                        objective.setCoefficient(variable, -1);
                    }
                    choices.add(new Choice(variable, move));
                    for (final var debit : preview.cpuRamDebits(placement)) {
                        final var rows = capacity.get(debit.resource());
                        final var constraint = debit.dimension() == CPU ? rows.cpu : rows.ram;
                        constraint.setCoefficient(variable, debit.amount());
                    }
                }
            }

            for (int attempt = 0; attempt < SOLVE_LIMIT_PER_SOURCE; attempt++) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                solver.setTimeLimit(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                final long started = System.nanoTime();
                solves++;
                final var status = solver.solve();
                solveNanos += System.nanoTime() - started;
                if (status == MPSolver.ResultStatus.INFEASIBLE || status == MPSolver.ResultStatus.NOT_SOLVED) {
                    return false;
                }
                if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                    throw new IllegalStateException("CBC returned " + status + " for proactive relocation");
                }
                final var selected = new ArrayList<MPVariable>();
                final var moves = new ArrayList<Engine.Relocation>();
                for (final var choice : choices) {
                    if (choice.variable.solutionValue() > .5) {
                        selected.add(choice.variable);
                        if (choice.move != null) {
                            moves.add(choice.move);
                        }
                    }
                }
                final long[] gain = new long[1];
                final Predicate<SchedulingContext> improves = state -> {
                    gain[0] = copies(state, planner, hosts) - baseline;
                    return gain[0] > 0;
                };
                if (selected.size() == victims.size() && !moves.isEmpty() && moves.size() <= maxMoves
                        && (engine.relocate(moves, improves)
                        || moves.size() == 2 && engine.relocate(moves.reversed(), improves))) {
                    applied++;
                    movedUnits += moves.size();
                    probeGain += gain[0];
                    return true;
                }
                rejectedPlans++;
                final var excluded = solver.makeConstraint(-MPSolver.infinity(), selected.size() - 1.0);
                for (final var variable : selected) {
                    excluded.setCoefficient(variable, 1);
                }
            }
            return false;
        } finally {
            solver.delete();
        }
    }

    private long copies(SchedulingContext state, GreedyPlanner planner, Set<Integer> hosts) {
        long total = 0;
        for (final int host : hosts) {
            final int hostCopies = copies(state, host);
            final var nodes = planner.numaNodes(host);
            if (nodes.length == 1) {
                total += hostCopies;
            } else {
                long nodeCopies = 0;
                for (int index = 1; index < nodes.length; index++) {
                    nodeCopies += copies(state, nodes[index]);
                }
                total += Math.min(hostCopies, nodeCopies);
            }
        }
        return total;
    }

    private int copies(SchedulingContext state, int resource) {
        return (int) Math.min(bound(state.available(resource, CPU)) / probeCpu,
                bound(state.available(resource, RAM)) / probeRam);
    }

    private static CapacityRows rows(MPSolver solver, Engine state, int resource) {
        return new CapacityRows(solver.makeConstraint(0, bound(state.available(resource, CPU))),
                solver.makeConstraint(0, bound(state.available(resource, RAM))));
    }

    private static double bound(double available) {
        return Math.min(Double.MAX_VALUE, available + 4 * Math.ulp(available));
    }

    private static MPSolver cbc() {
        final var solver = MPSolver.createSolver("CBC");
        if (solver == null) {
            throw new IllegalStateException("The OR-Tools native library does not provide CBC");
        }
        return solver;
    }

    @Override
    public long plannerCalls() {
        return greedy.plannerCalls() + plannerCalls;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("base", "geometric-best-fit", "solver", solverVersion);
    }

    @Override
    public Map<String, Long> counters() {
        return Map.of("proactive_runs", runs, "proactive_solves", solves, "proactive_applied", applied,
                "proactive_moved_units", movedUnits, "proactive_probe_gain", probeGain,
                "proactive_rejected_plans", rejectedPlans, "proactive_repair_nanos", repairNanos,
                "proactive_solve_nanos", solveNanos);
    }

    private record Choice(MPVariable variable, Engine.Relocation move) {
    }

    private record CapacityRows(MPConstraint cpu, MPConstraint ram) {
        private void add(MPVariable variable, double cores, double memory) {
            cpu.setCoefficient(variable, cores);
            ram.setCoefficient(variable, memory);
        }
    }
}
