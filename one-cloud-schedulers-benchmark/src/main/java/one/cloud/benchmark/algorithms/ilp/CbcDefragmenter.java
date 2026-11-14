package one.cloud.benchmark.algorithms.ilp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;

import one.cloud.benchmark.engine.Defragmenter;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.CPU;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.RAM;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.SOURCE_LIMIT;

public final class CbcDefragmenter implements Defragmenter {
    public static final int MAX_MOVES = 2;
    private static final int SOLVE_LIMIT_PER_SOURCE = 3;

    private final Engine engine;
    private final RelocationCandidates candidates;
    private final String solverVersion;
    private final int maxMoves, timeoutMs;
    private final double probeCpu, probeRam;
    private long runs, solves, applied, movedUnits, probeGain, rejectedPlans, repairNanos, solveNanos, plannerCalls;
    private long reactiveRuns, reactiveSolves, reactiveApplied, reactiveMoves;

    public CbcDefragmenter(Engine engine, int maxMoves, int timeoutMs, double probeCpu, double probeRam) {
        if (maxMoves < 1 || maxMoves > MAX_MOVES || timeoutMs < 1 || !Double.isFinite(probeCpu)
                || !Double.isFinite(probeRam) || probeCpu <= 0 || probeRam <= 0) {
            throw new IllegalArgumentException("Invalid CBC defragmentation settings");
        }
        this.engine = engine;
        candidates = new RelocationCandidates(engine);
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
    public void defragment(Optional<Request> incoming) throws Exception {
        if (incoming.isEmpty()) {
            proactive(null);
            return;
        }
        reactiveRuns++;
        if (engine.model().dimensionCount >= RAM) {
            repair(incoming.orElseThrow(), null, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }
    }

    void proactive(Request excluded) throws Exception {
        runs++;
        final long started = System.nanoTime();
        try {
            if (engine.model().dimensionCount >= RAM) {
                repair(null, excluded, started + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            }
        } finally {
            repairNanos += System.nanoTime() - started;
        }
    }

    private void repair(Request incoming, Request excluded, long deadline) throws Exception {
        final var hosts = candidates.rankedHosts();
        final var victimsByHost = candidates.victims(incoming, excluded);
        int sources = 0;
        for (final int source : hosts) {
            if (sources == SOURCE_LIMIT || deadline - System.nanoTime() <= 0) {
                return;
            }
            final var victims = victimsByHost.get(source);
            if (victims != null && (incoming == null || candidates.eligible(incoming, source))) {
                sources++;
                if (trySource(incoming, source, victims, hosts, deadline)) {
                    return;
                }
            }
        }
    }

    private boolean trySource(Request incoming, int source, List<Request> victims, List<Integer> hosts, long deadline)
            throws Exception {
        final var preview = engine.relocationPreview(victims);
        if (preview == null) {
            return false;
        }
        final var planner = new GreedyPlanner(preview);
        final var plans = new ArrayList<List<Assignment[]>>();
        final Set<Integer> modeledHosts = new LinkedHashSet<>();
        modeledHosts.add(source);
        final var requests = new ArrayList<>(victims);
        if (incoming != null) {
            requests.add(incoming);
        }
        preview.schedule(new SchedulingAlgorithm(preview) {
            @Override
            public void schedule(Request victim) {
                if (deadline - System.nanoTime() <= 0) {
                    return;
                }
                final var options = new ArrayList<Assignment[]>();
                if (victim == incoming) {
                    options.addAll(RelocationCandidates.plans(incoming, source, planner));
                } else {
                    options.add(new Assignment[]{engine.placement(victim.resource(0))});
                    for (final var destination : candidates.destinations(victim, source, hosts, planner)) {
                        options.add(destination);
                        modeledHosts.add(destination[0].host());
                    }
                }
                plans.add(options);
            }
        }, requests.toArray(Request[]::new));
        plannerCalls += planner.planCalls();
        return plans.size() == requests.size() && modeledHosts.size() > 1
                && (incoming == null || !plans.getLast().isEmpty()) && deadline - System.nanoTime() > 0
                && solve(preview, planner, incoming, victims, plans, modeledHosts, deadline);
    }

    private boolean solve(Engine preview, GreedyPlanner planner, Request incoming, List<Request> victims,
            List<List<Assignment[]>> plans, Set<Integer> hosts, long deadline) throws Exception {
        final boolean proactive = incoming == null;
        final long baseline = proactive ? copies(engine, planner, hosts) : 0;
        final var solver = cbc();
        try {
            solver.setNumThreads(1);
            final var objective = solver.objective();
            objective.setMaximization();
            final var moveLimit = solver.makeConstraint(proactive ? 0 : 1, maxMoves);
            final var improvement = proactive ? solver.makeConstraint(baseline + 1.0, MPSolver.infinity()) : null;
            final var capacity = new LinkedHashMap<Integer, CapacityRows>();
            for (final int host : hosts) {
                final var hostRows = rows(solver, preview, host);
                capacity.put(host, hostRows);
                final var nodes = planner.numaNodes(host);
                for (final int numa : nodes) {
                    if (numa != 0) {
                        capacity.put(numa, rows(solver, preview, numa));
                    }
                    if (proactive && (numa != 0 || nodes.length == 1)) {
                        final var copies = solver.makeIntVar(0, copies(preview, numa == 0 ? host : numa), "");
                        hostRows.add(copies, probeCpu, probeRam);
                        if (numa != 0) {
                            capacity.get(numa).add(copies, probeCpu, probeRam);
                        }
                        objective.setCoefficient(copies, maxMoves + 1);
                        improvement.setCoefficient(copies, 1);
                    }
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
                    if (group < victims.size() && option != 0) {
                        move = new Engine.Relocation(victims.get(group), placement);
                        moveLimit.setCoefficient(variable, 1);
                        objective.setCoefficient(variable, -1);
                    }
                    choices.add(new Choice(variable, move, group == victims.size() ? placement : null));
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
                final var status = solver.solve();
                if (proactive) {
                    solves++;
                    solveNanos += System.nanoTime() - started;
                } else {
                    reactiveSolves++;
                }
                if (status == MPSolver.ResultStatus.INFEASIBLE || status == MPSolver.ResultStatus.NOT_SOLVED) {
                    return false;
                }
                if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                    throw new IllegalStateException("CBC returned " + status + " for defragmentation");
                }
                final var selected = new ArrayList<MPVariable>();
                final var moves = new ArrayList<Engine.Relocation>();
                Assignment[] admission = null;
                for (final var choice : choices) {
                    if (choice.variable.solutionValue() > .5) {
                        selected.add(choice.variable);
                        if (choice.move != null) {
                            moves.add(choice.move);
                        }
                        if (choice.admission != null) {
                            admission = choice.admission;
                        }
                    }
                }
                final long[] gain = new long[1];
                final Predicate<SchedulingContext> improves = state -> {
                    gain[0] = copies(state, planner, hosts) - baseline;
                    return gain[0] > 0;
                };
                if (selected.size() == plans.size() && !moves.isEmpty() && moves.size() <= maxMoves
                        && (apply(incoming, admission, moves, improves)
                        || moves.size() == 2 && apply(incoming, admission, moves.reversed(), improves))) {
                    if (proactive) {
                        applied++;
                        movedUnits += moves.size();
                        probeGain += gain[0];
                    } else {
                        reactiveApplied++;
                        reactiveMoves += moves.size();
                    }
                    return true;
                }
                if (proactive) {
                    rejectedPlans++;
                }
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

    private boolean apply(Request incoming, Assignment[] admission, List<Engine.Relocation> moves,
            Predicate<SchedulingContext> improves) throws Exception {
        return incoming == null ? engine.relocate(moves, improves)
                : admission != null && engine.relocateAndPlace(incoming, admission, moves);
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
        return plannerCalls;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("solver", solverVersion);
    }

    public String solverVersion() {
        return solverVersion;
    }

    @Override
    public Map<String, Long> counters() {
        final var result = new LinkedHashMap<>(proactiveCounters());
        result.put("yp_reactive_runs", reactiveRuns);
        result.put("yp_reactive_solves", reactiveSolves);
        result.put("yp_reactive_applied", reactiveApplied);
        result.put("yp_reactive_moved_units", reactiveMoves);
        return Map.copyOf(result);
    }

    Map<String, Long> proactiveCounters() {
        return Map.of("proactive_runs", runs, "proactive_solves", solves, "proactive_applied", applied,
                "proactive_moved_units", movedUnits, "proactive_probe_gain", probeGain,
                "proactive_rejected_plans", rejectedPlans, "proactive_repair_nanos", repairNanos,
                "proactive_solve_nanos", solveNanos);
    }

    private record Choice(MPVariable variable, Engine.Relocation move, Assignment[] admission) {
    }

    private record CapacityRows(MPConstraint cpu, MPConstraint ram) {
        private void add(MPVariable variable, double cores, double memory) {
            cpu.setCoefficient(variable, cores);
            ram.setCoefficient(variable, memory);
        }
    }
}
