package one.cloud.benchmark.algorithms.ilp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.integer.IntegerStrategy;

import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.DESTINATION_LIMIT;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.NUMA_LIMIT;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.SOURCE_LIMIT;
import static one.cloud.benchmark.algorithms.ilp.RelocationCandidates.VICTIM_LIMIT;

public final class ReactiveIlp extends SchedulingAlgorithm {
    public static final int DEFAULT_MAX_MOVES = 2;
    public static final int DEFAULT_TIMEOUT_MS = 200;
    private static final int SOLVE_LIMIT_PER_SOURCE = 3;

    private final Engine engine;
    private final RelocationCandidates candidates;
    private final GeometricBestFit greedy;
    private final int maxMoves, timeoutMs;
    private long fallbacks, solves, rescued, movedUnits, rejectedPlans, solveNanos, repairNanos, repairPlannerCalls;
    private long infeasibleModels, solvesWithoutIncumbent, exhaustedBudgets;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("reactive-ilp", "Reactive ILP",
                List.of(AlgorithmParameters.SEED,
                        Parameter.integer("maxMoves", "Maximum moves", "Максимум переездов на один запрос.",
                                DEFAULT_MAX_MOVES, 1, DEFAULT_MAX_MOVES),
                        Parameter.integer("timeoutMs", "Repair budget (ms)", "Бюджет поиска после отказа в миллисекундах.",
                                DEFAULT_TIMEOUT_MS, 1, Integer.MAX_VALUE)),
                (context, catalogue, parameters) -> new ReactiveIlp(context, parameters.get("seed").getAsLong(),
                        parameters.get("maxMoves").getAsInt(), parameters.get("timeoutMs").getAsInt()));
    }

    public ReactiveIlp(SchedulingContext context, long seed, int maxMoves, int timeoutMs) {
        super(context);
        if (!(context instanceof Engine placementEngine)) {
            throw new IllegalArgumentException("Reactive ILP requires the placement engine");
        }
        if (maxMoves < 1 || maxMoves > DEFAULT_MAX_MOVES || timeoutMs < 1) {
            throw new IllegalArgumentException("Reactive ILP requires 1..2 moves and a positive time budget");
        }
        engine = placementEngine;
        candidates = new RelocationCandidates(engine);
        greedy = new GeometricBestFit(context, seed);
        this.maxMoves = maxMoves;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void schedule(Request request) throws Exception {
        greedy.schedule(request);
        if (engine.placement(request.resource(0)) != null) {
            return;
        }
        fallbacks++;
        final long start = System.nanoTime();
        try {
            final long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            if (tryRepair(request, deadline)) {
                rescued++;
            } else if (System.nanoTime() >= deadline) {
                exhaustedBudgets++;
            }
        } finally {
            repairNanos += System.nanoTime() - start;
        }
    }

    private boolean tryRepair(Request request, long deadline) throws Exception {
        final var hosts = candidates.rankedHosts();
        final var victimsByHost = candidates.victims(request, null);
        int sources = 0;
        for (final int host : hosts) {
            if (System.nanoTime() >= deadline || sources == SOURCE_LIMIT) {
                break;
            }
            final var victims = victimsByHost.get(host);
            if (victims == null || !candidates.eligible(request, host)) {
                continue;
            }
            sources++;
            if (repair(request, host, victims, hosts, deadline)) {
                return true;
            }
        }
        return false;
    }

    private boolean repair(Request incoming, int source, List<Request> victims, List<Integer> hosts, long deadline)
            throws Exception {
        final var preview = engine.relocationPreview(victims);
        if (preview == null) {
            return false;
        }
        final var planner = new GreedyPlanner(preview);
        final var choices = new ArrayList<List<Choice>>();
        final var requests = new ArrayList<Request>(victims.size() + 1);
        requests.add(incoming);
        requests.addAll(victims);
        preview.schedule(new SchedulingAlgorithm(preview) {
            @Override
            public void schedule(Request request) {
                if (request == incoming) {
                    final var options = new ArrayList<Choice>();
                    for (final var plans : RelocationCandidates.plans(request, source, planner)) {
                        options.add(new Choice(plans, false));
                    }
                    choices.add(options);
                } else if (!choices.getFirst().isEmpty() && System.nanoTime() < deadline) {
                    final var options = new ArrayList<Choice>();
                    options.add(new Choice(new Assignment[]{engine.placement(request.resource(0))}, false));
                    for (final var plans : candidates.destinations(request, source, hosts, planner)) {
                        options.add(new Choice(plans, true));
                    }
                    choices.add(options);
                }
            }
        }, requests.toArray(Request[]::new));
        repairPlannerCalls += planner.planCalls();
        if (choices.getFirst().isEmpty() || choices.size() != requests.size() || System.nanoTime() >= deadline
                || choices.stream().skip(1).noneMatch(options -> options.size() > 1)) {
            return false;
        }
        return solve(preview, requests, choices, deadline);
    }

    private boolean solve(Engine preview, List<Request> requests, List<List<Choice>> groups, long deadline)
            throws Exception {
        final var ilp = new ExpressionsBasedModel();
        try {
            ilp.options.integer(IntegerStrategy.newConfigurable().withParallelism(() -> 1));
            final var moves = ilp.newExpression("moves").lower(1).upper(maxMoves);
            final var capacity = new LinkedHashMap<CapacityRow, Expression>();
            int variables = 0;
            for (int group = 0; group < groups.size(); group++) {
                final var exactlyOne = ilp.newExpression("request-" + group).level(1);
                for (final var choice : groups.get(group)) {
                    final var variable = ilp.newVariable("choice-" + variables++).binary();
                    exactlyOne.set(variable, 1);
                    if (choice.move) {
                        moves.set(variable.weight(1), 1);
                    }
                    for (final var debit : preview.cpuRamDebits(choice.plans)) {
                        final var row = new CapacityRow(debit.resource(), debit.dimension());
                        capacity.computeIfAbsent(row, key -> ilp.newExpression("capacity-" + key.resource + "-" + key.dimension)
                                .upper(capacityBound(preview.available(key.resource, key.dimension))))
                                .add(variable, debit.amount());
                    }
                }
            }
            for (int attempt = 0; attempt < SOLVE_LIMIT_PER_SOURCE; attempt++) {
                final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return false;
                }
                ilp.options.time_abort = remaining;
                ilp.options.time_suffice = remaining;
                solves++;
                final long start = System.nanoTime();
                final var solution = ilp.minimise();
                solveNanos += System.nanoTime() - start;
                if (!solution.getState().isFeasible()) {
                    if (solution.getState() == Optimisation.State.INFEASIBLE) {
                        infeasibleModels++;
                    } else {
                        solvesWithoutIncumbent++;
                    }
                    return false;
                }
                final var selected = new ArrayList<Integer>();
                final var relocations = new ArrayList<Engine.Relocation>();
                Assignment[] incoming = null;
                int index = 0;
                for (int group = 0; group < groups.size(); group++) {
                    for (final var choice : groups.get(group)) {
                        if (solution.doubleValue(index) > 0.5) {
                            selected.add(index);
                            if (group == 0) {
                                incoming = choice.plans;
                            } else if (choice.move) {
                                relocations.add(new Engine.Relocation(requests.get(group), choice.plans));
                            }
                        }
                        index++;
                    }
                }
                if (incoming != null && selected.size() == groups.size() && !relocations.isEmpty()
                        && relocations.size() <= maxMoves
                        && (engine.relocateAndPlace(requests.getFirst(), incoming, relocations)
                        || relocations.size() == 2
                        && engine.relocateAndPlace(requests.getFirst(), incoming, relocations.reversed()))) {
                    movedUnits += relocations.size();
                    return true;
                }
                rejectedPlans++;
                // Reject this combination while keeping the candidate assignments for the next solve.
                final var noGood = ilp.newExpression("rejected-" + attempt).upper(selected.size() - 1);
                for (final int chosen : selected) {
                    noGood.set(chosen, 1);
                }
            }
            return false;
        } finally {
            ilp.dispose();
        }
    }

    private static double capacityBound(double available) {
        return Math.min(Double.MAX_VALUE, available + Math.ulp(available) * 4);
    }

    @Override
    public long plannerCalls() {
        return greedy.plannerCalls() + repairPlannerCalls;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("base", "geometric-best-fit", "solver", "ojalgo", "ilp_max_moves", Integer.toString(maxMoves),
                "ilp_timeout_ms", Integer.toString(timeoutMs), "ilp_source_limit", Integer.toString(SOURCE_LIMIT),
                "ilp_victim_limit", Integer.toString(VICTIM_LIMIT), "ilp_destination_limit", Integer.toString(DESTINATION_LIMIT),
                "ilp_numa_limit", Integer.toString(NUMA_LIMIT), "ilp_solve_limit_per_source", Integer.toString(SOLVE_LIMIT_PER_SOURCE));
    }

    @Override
    public Map<String, Long> counters() {
        return Map.of("ilp_fallbacks", fallbacks, "ilp_solves", solves, "ilp_rescued", rescued,
                "ilp_moved_units", movedUnits, "ilp_rejected_plans", rejectedPlans, "ilp_solve_nanos", solveNanos,
                "ilp_infeasible", infeasibleModels, "ilp_no_incumbent", solvesWithoutIncumbent,
                "ilp_budget_exhausted", exhaustedBudgets, "ilp_repair_nanos", repairNanos);
    }

    private record Choice(Assignment[] plans, boolean move) {
    }

    private record CapacityRow(int resource, int dimension) {
    }
}
