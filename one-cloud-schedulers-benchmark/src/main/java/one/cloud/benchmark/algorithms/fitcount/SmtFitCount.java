package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

public final class SmtFitCount extends SchedulingAlgorithm {
    private static final int MAX_UNITS = 8;
    private static final int MAX_PLANS = 128;
    private static final int MAX_MEMBERS = 128;
    private static final long MAX_INTEGER = 1L << 48;
    private final SplittableRandom random;
    private final FitCount fallback;
    private final Request[] catalogue;
    private final int cap, cacheRows, nodeLimit, solutionLimit, resourceLimit;
    private final boolean integerModel;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private String solverVersion = "unused";
    private long trialPlannerCalls;

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition("smt", "SMT / Z3 reference",
                List.of(AlgorithmParameters.BULK_SEED, AlgorithmParameters.BULK_FIT_CAP,
                        AlgorithmParameters.BULK_CACHE_ROWS,
                        Parameter.integer("searchNodes", "Candidate search nodes", "Бюджет построения полных планов.",
                                100_000, 1, 1_000_000),
                        Parameter.integer("solutions", "Checked solutions", "Максимум проверяемых решений Z3.",
                                64, 1, 4096),
                        Parameter.integer("rlimit", "Z3 resource limit", "Общий бюджет Z3 на окно.",
                                1_000_000, 1, Integer.MAX_VALUE)),
                (context, catalogue, parameters) -> new SmtFitCount(context, parameters.get("seed").getAsLong(),
                        catalogue, parameters.get("fitCap").getAsInt(), parameters.get("cacheRows").getAsInt(),
                        parameters.get("searchNodes").getAsInt(), parameters.get("solutions").getAsInt(),
                        parameters.get("rlimit").getAsInt()));
    }

    public SmtFitCount(SchedulingContext context, long seed, Request[] catalogue, int cap, int cacheRows) {
        this(context, seed, catalogue, cap, cacheRows, 100_000, 64, 1_000_000);
    }

    SmtFitCount(SchedulingContext context, long seed, Request[] catalogue, int cap, int cacheRows,
            int nodeLimit, int solutionLimit, int resourceLimit) {
        super(context);
        random = new SplittableRandom(seed);
        if (nodeLimit < 1 || solutionLimit < 1 || resourceLimit < 1) {
            throw new IllegalArgumentException("SMT budgets must be positive");
        }
        this.catalogue = catalogue.clone();
        this.cap = cap;
        this.cacheRows = cacheRows;
        this.nodeLimit = nodeLimit;
        this.solutionLimit = solutionLimit;
        this.resourceLimit = resourceLimit;
        fallback = new FitCount(context, seed, catalogue, cap, cacheRows);
        final var allocations = context.model().allocations;
        boolean exact = true;
        for (int index = 0; index < allocations.type.length; index++) {
            if (allocations.type[index] == VCORES_VALUE || allocations.type[index] == MEM_MIB_VALUE) {
                exact &= integer(allocations.amount[index]);
            }
        }
        integerModel = exact;
    }

    @Override
    public void schedule(Request request) throws Exception {
        schedule(new Request[]{request});
    }

    @Override
    public void schedule(Request[] requests) throws Exception {
        if (requests.length == 0) {
            return;
        }
        increment("windows");
        final String unsupported = unsupported(requests);
        if (unsupported != null) {
            increment("fallback_" + unsupported);
            fallback.schedule(requests);
            return;
        }
        final Engine.Trial trial;
        try {
            trial = context.trial(context.model());
        } catch (UnsupportedOperationException unavailable) {
            increment("fallback_trial_unavailable");
            fallback.schedule(requests);
            return;
        }
        final var state = trial.state();
        final var baseline = new FitCount(state, random.nextLong(), catalogue, cap, cacheRows);
        final boolean singleton = requests.length == 1 && requests[0].size() == 1;
        final var planner = singleton ? null : new GreedyPlanner(state);
        Assignment[][] winner;
        try {
            state.schedule(baseline, requests);
            final var incumbent = snapshot(state, requests);
            winner = incumbent;
            if (singleton) {
                // FitCount already considers every host/NUMA plan for one scalar member.
                increment("singleton_optima");
            } else {
                final var samples = orderedSamples();
                long[] bestScore = score(planner, samples, null);
                int bestAdmission = admitted(winner);
                trial.reset();
                try {
                    final var pool = new Pool(state, planner, requests, incumbent);
                    try {
                        pool.generate();
                    } finally {
                        counts.merge("candidate_nodes", (long) pool.visited, Long::sum);
                        counts.merge("candidate_plans", (long) pool.total, Long::sum);
                    }
                    final int[] sizes = pool.plans.stream().mapToInt(List::size).toArray();
                    final var rows = pool.rows();
                    try (final var solver = new SmtSolver(sizes, rows, resourceLimit)) {
                        solverVersion = SmtSolver.version();
                        increment("optimized_windows");
                        boolean exhausted = false;
                        for (int attempt = 0; attempt < solutionLimit; attempt++) {
                            increment("solver_calls");
                            final int[] choices = solver.next(bestAdmission);
                            if (choices == null) {
                                increment("pool_optima");
                                exhausted = true;
                                break;
                            }
                            final var proposal = new Assignment[requests.length][];
                            for (int unit = 0; unit < requests.length; unit++) {
                                if (choices[unit] >= 0) {
                                    proposal[unit] = pool.plans.get(unit).get(choices[unit]).assignments;
                                }
                            }
                            trial.reset();
                            if (!replay(state, requests, proposal)) {
                                increment("rejected_solutions");
                                continue;
                            }
                            final int admission = admitted(proposal);
                            final long[] proposedScore = score(planner, samples,
                                    admission > bestAdmission ? null : bestScore);
                            if (proposedScore != null) {
                                winner = proposal;
                                bestAdmission = admission;
                                bestScore = proposedScore;
                            }
                        }
                        if (!exhausted) {
                            increment("solution_budget");
                        }
                    }
                } catch (Unsupported unsupportedCase) {
                    increment("fallback_" + unsupportedCase.getMessage());
                } catch (SmtSolver.Limit exhausted) {
                    increment("resource_budget");
                    winner = incumbent;
                } catch (SmtSolver.Unavailable unavailable) {
                    increment("fallback_solver_unavailable");
                    winner = incumbent;
                }
            }
            if (winner != incumbent) {
                increment("improved_windows");
            }
            trial.reset();
            if (!replay(state, requests, winner)) {
                throw new IllegalStateException("Saved SMT result failed prefix replay");
            }
        } finally {
            trialPlannerCalls += baseline.plannerCalls() + (planner == null ? 0 : planner.planCalls());
            trial.reset();
        }
        for (int unit = 0; unit < requests.length; unit++) {
            if (winner[unit] != null) {
                context.place(requests[unit], winner[unit]);
            }
        }
    }

    private String unsupported(Request[] requests) {
        if (requests.length > MAX_UNITS) {
            return "window_limit";
        }
        if (!integerModel) {
            return "non_integer";
        }
        final var model = context.model();
        int members = 0;
        long cpu = 0, memory = 0;
        for (final var request : requests) {
            if (request.size() > MAX_MEMBERS - members) {
                return "member_limit";
            }
            members += request.size();
            for (int member = 0; member < request.size(); member++) {
                final int resource = request.resource(member);
                final int profile = model.constraints[resource];
                if (model.rules.diskOffset[profile] != model.rules.diskOffset[profile + 1]) {
                    return "unsupported_resources";
                }
                final int allocation = model.alloc[resource];
                for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
                    final int dimension = model.allocations.type[index];
                    final double amount = model.allocations.amount[index];
                    if (dimension == VCORES_VALUE) {
                        cpu += (long) amount;
                    } else if (dimension == MEM_MIB_VALUE) {
                        memory += (long) amount;
                    } else if (amount != 0) {
                        return "unsupported_resources";
                    }
                    if (cpu > MAX_INTEGER || memory > MAX_INTEGER) {
                        return "numeric_range";
                    }
                }
            }
        }
        return null;
    }

    private Request[] orderedSamples() {
        final var cache = fallback.cache();
        final var samples = new ArrayList<Request>();
        cache.beginOrder();
        for (var sample = cache.next(); sample != null; sample = cache.next()) {
            samples.add(sample.request);
        }
        return samples.toArray(Request[]::new);
    }

    private long[] score(GreedyPlanner planner, Request[] samples, long[] incumbent) {
        final var result = new long[samples.length];
        boolean better = incumbent == null;
        for (int sample = 0; sample < samples.length; sample++) {
            for (int host = 1; host <= context.model().hostCount; host++) {
                result[sample] += planner.fitCount(samples[sample], host, cap);
            }
            if (!better) {
                if (result[sample] < incumbent[sample]) {
                    return null;
                }
                better = result[sample] > incumbent[sample];
            }
        }
        return better ? result : null;
    }

    private static int admitted(Assignment[][] plans) {
        int count = 0;
        for (final var plan : plans) {
            if (plan != null) {
                count++;
            }
        }
        return count;
    }

    private static Assignment[][] snapshot(Engine state, Request[] requests) {
        final var result = new Assignment[requests.length][];
        for (int unit = 0; unit < requests.length; unit++) {
            final var request = requests[unit];
            if (state.placement(request.resource(0)) != null) {
                result[unit] = new Assignment[request.size()];
                for (int member = 0; member < request.size(); member++) {
                    result[unit][member] = state.placement(request.resource(member)).copy();
                }
            }
        }
        return result;
    }

    private static boolean replay(Engine state, Request[] requests, Assignment[][] plans) throws Exception {
        final boolean[] valid = {true};
        state.schedule(new SchedulingAlgorithm(state) {
            private int index;

            @Override
            public void schedule(Request request) {
                final var plan = plans[index++];
                if (valid[0] && plan != null) {
                    valid[0] = state.canPlace(request, plan);
                    if (valid[0]) {
                        state.place(request, plan);
                    }
                }
            }
        }, requests);
        return valid[0];
    }

    private final class Pool {
        private final Engine state;
        private final GreedyPlanner planner;
        private final Request[] requests;
        private final Assignment[][] incumbent;
        private final List<List<Plan>> plans = new ArrayList<>();
        private int visited, total;

        Pool(Engine state, GreedyPlanner planner, Request[] requests, Assignment[][] incumbent) {
            this.state = state;
            this.planner = planner;
            this.requests = requests;
            this.incumbent = incumbent;
        }

        void generate() {
            for (int unit = 0; unit < requests.length; unit++) {
                plans.add(new ArrayList<>());
                if (incumbent[unit] != null) {
                    add(unit, incumbent[unit]);
                }
            }
            for (int unit = 0; unit < requests.length; unit++) {
                enumerate(unit, 0, new Assignment[requests[unit].size()]);
            }
        }

        private void enumerate(int unit, int member, Assignment[] assignments) {
            charge();
            final var model = state.model();
            if (member == assignments.length) {
                add(unit, assignments);
                return;
            }
            final int resource = requests[unit].resource(member);
            final int profile = model.constraints[resource];
            for (int host = 1; host <= model.hostCount; host++) {
                charge();
                if (!model.candidates.allows(model.rules.candidates[profile], host)
                        || model.rules.pinnedHost[profile] != 0 && model.rules.pinnedHost[profile] != host) {
                    continue;
                }
                for (final int numa : planner.numaNodes(host)) {
                    assignments[member] = new Assignment(resource, host, numa, new int[]{0}, new int[0]);
                    enumerate(unit, member + 1, assignments);
                }
            }
        }

        private void charge() {
            if (++visited > nodeLimit) {
                throw new Unsupported("work_budget");
            }
        }

        private void add(int unit, Assignment[] assignments) {
            if (incumbent[unit] != null && !plans.get(unit).isEmpty()) {
                boolean same = true;
                for (int member = 0; member < assignments.length; member++) {
                    same &= incumbent[unit][member].host() == assignments[member].host()
                            && incumbent[unit][member].numa() == assignments[member].numa();
                }
                if (same) {
                    return;
                }
            }
            final var debits = state.tryCpuRamDebits(assignments);
            if (debits == null) {
                return;
            }
            if (total == MAX_PLANS) {
                throw new Unsupported("plan_limit");
            }
            final var owned = Arrays.stream(assignments).map(Assignment::copy).toArray(Assignment[]::new);
            plans.get(unit).add(new Plan(owned, debits));
            total++;
        }

        List<SmtSolver.Row> rows() {
            final var rows = new TreeMap<Long, long[]>();
            int variable = 0;
            for (final var choices : plans) {
                for (final var plan : choices) {
                    for (final var debit : plan.debits) {
                        if (!integer(debit.amount())) {
                            throw new Unsupported("non_integer");
                        }
                        final long key = (long) debit.resource() << 32 | debit.dimension();
                        rows.computeIfAbsent(key, ignored -> new long[total])[variable] = (long) debit.amount();
                    }
                    variable++;
                }
            }
            final var result = new ArrayList<SmtSolver.Row>();
            for (final var row : rows.entrySet()) {
                final long key = row.getKey();
                final double available = state.available((int) (key >>> 32), (int) key);
                if (!integer(available)) {
                    throw new Unsupported("non_integer");
                }
                result.add(new SmtSolver.Row(row.getValue(), (long) available));
            }
            return result;
        }
    }

    private record Plan(Assignment[] assignments, List<Engine.ScalarDebit> debits) {
    }

    private static final class Unsupported extends RuntimeException {
        Unsupported(String reason) {
            super(reason);
        }
    }

    private static boolean integer(double amount) {
        return Double.isFinite(amount) && amount >= 0 && amount <= MAX_INTEGER && amount == Math.rint(amount);
    }

    private void increment(String key) {
        counts.merge(key, 1L, Long::sum);
    }

    @Override
    public void capacityChanged(int host) {
        fallback.capacityChanged(host);
    }

    @Override
    public long plannerCalls() {
        return fallback.plannerCalls() + trialPlannerCalls;
    }

    @Override
    public Map<String, Long> counters() {
        return Map.copyOf(counts);
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("backend", "z3-smtlib", "backend_version", solverVersion,
                "geometry", "integer-cpu-ram", "max_units", Integer.toString(MAX_UNITS),
                "max_plans", Integer.toString(MAX_PLANS), "max_members", Integer.toString(MAX_MEMBERS),
                "watchdog_seconds", "30", "objective", "admission-then-frozen-lexicographic-fitcount");
    }
}
