package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmParameters;
import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class Rollout extends SchedulingAlgorithm {
    private final FitCount fallback;
    private final Request[] catalogue;
    private final RolloutPrior prior;
    private final long seed;
    private final int cap, cacheRows, candidateLimit, scenarioCount, horizon;
    private long attempts, declines, unsupported, baselineWins, branches, continuationCalls, continuationAdmitted;
    private long trialPlannerCalls;
    private long[] tracedAttempts = new long[0];
    private int[] pathLengths = new int[0];
    private int decisions;

    public static AlgorithmDefinition definition() {
        final var descriptor = new AlgorithmRegistry.Descriptor(
                "rollout",
                "Rollout + FitCount",
                AlgorithmParameters.ROLLOUT,
                true,
                1);

        return new AlgorithmDefinition(descriptor, (context, catalogue, parameters, priorFile) -> {
            final int horizon = parameters.get("horizon").getAsInt();
            final var prior = RolloutPrior.load(priorFile, context.model(), horizon);

            return new Rollout(
                    context,
                    parameters.get("seed").getAsLong(),
                    catalogue,
                    prior,
                    parameters.get("fitCap").getAsInt(),
                    parameters.get("cacheRows").getAsInt(),
                    parameters.get("candidates").getAsInt(),
                    parameters.get("scenarios").getAsInt(),
                    horizon);
        });
    }

    public Rollout(SchedulingContext context, long seed, Request[] catalogue, RolloutPrior prior,
            int maxFitCount, int maxCachedShapes) {
        this(context, seed, catalogue, prior, maxFitCount, maxCachedShapes, 8, 8, 8);
    }

    public Rollout(SchedulingContext context, long seed, Request[] catalogue, RolloutPrior prior,
            int maxFitCount, int maxCachedShapes, int candidates, int scenarios, int horizon) {
        super(context, seed);
        if (candidates <= 0 || scenarios <= 0 || horizon <= 0
                || (long) scenarios * (horizon + 16L) > Runtime.getRuntime().maxMemory() / 128) {
            throw new IllegalArgumentException("Rollout limits must be positive and fit the configured heap budget");
        }
        if (prior.source() != context.model() || prior.horizon() != horizon) {
            throw new IllegalArgumentException("Rollout prior belongs to a different model or horizon");
        }
        this.catalogue = catalogue.clone();
        this.prior = prior;
        this.seed = seed;
        cap = maxFitCount;
        cacheRows = maxCachedShapes;
        candidateLimit = candidates;
        scenarioCount = scenarios;
        this.horizon = horizon;
        fallback = new FitCount(context, seed, this.catalogue, maxFitCount, maxCachedShapes);
    }

    @Override
    public void schedule(Request request) throws Exception {
        final long operation = attempts++;
        if (!prior.unsupportedReason().isEmpty()) {
            unsupported++;
            final var plan = fallback.select(request);
            if (plan == null) {
                declines++;
            } else {
                context.place(request, plan);
            }
            return;
        }
        final long decisionSeed = mix64(seed ^ mix64(operation));
        final var candidates = fallback.selectCandidates(request, candidateLimit,
                mix64(decisionSeed ^ 0xA0761D6478BD642FL));
        if (candidates.length == 0) {
            declines++;
            return;
        }
        reserveTrace();
        final var paths = new Request[scenarioCount][];
        final var policySeeds = new long[scenarioCount];
        for (int scenario = 0; scenario < scenarioCount; scenario++) {
            final long scenarioSeed = mix64(decisionSeed ^ mix64(scenario));
            paths[scenario] = prior.sample(scenarioSeed);
            policySeeds[scenario] = mix64(scenarioSeed ^ 0xD1B54A32D192ED03L);
            pathLengths[decisions * scenarioCount + scenario] = paths[scenario].length;
        }
        tracedAttempts[decisions++] = operation;
        final var trial = context.trial(prior.model());
        int winner = 0;
        long best = Long.MIN_VALUE;
        for (int candidate = 0; candidate < candidates.length; candidate++) {
            long admitted = 0;
            final var plans = candidates[candidate];
            for (int scenario = 0; scenario < scenarioCount; scenario++) {
                final var state = trial.state();
                final var continuation = new FitCount(state, policySeeds[scenario], catalogue, cap, cacheRows);
                try (final var policy = new SchedulingAlgorithm(state, policySeeds[scenario]) {
                    @Override
                    public void schedule(Request pending) {
                        if (pending == request) {
                            context.place(pending, plans);
                        } else {
                            continuation.schedule(pending);
                        }
                    }

                    @Override
                    public void placed(Assignment placement) {
                        continuation.placed(placement);
                    }

                    @Override
                    public void removed(Assignment placement) {
                        continuation.removed(placement);
                    }
                }) {
                    state.schedule(policy, request);
                    branches++;
                    for (final var future : paths[scenario]) {
                        state.schedule(policy, future);
                        continuationCalls++;
                        if (state.placement(future.resource(0)) != null) {
                            admitted++;
                            continuationAdmitted++;
                        }
                    }
                } finally {
                    trialPlannerCalls = Math.addExact(trialPlannerCalls, continuation.plannerCalls());
                    trial.reset();
                }
            }
            if (admitted > best) {
                best = admitted;
                winner = candidate;
            }
        }
        if (winner == 0) {
            baselineWins++;
        }
        context.place(request, candidates[winner]);
    }

    @Override
    public void placed(Assignment placement) {
        fallback.placed(placement);
    }

    @Override
    public void removed(Assignment placement) {
        fallback.removed(placement);
    }

    @Override
    public long plannerCalls() {
        return Math.addExact(fallback.plannerCalls(), trialPlannerCalls);
    }

    @Override
    public Map<String, String> parameters() {
        return Map.ofEntries(
                Map.entry("profile", "rollout-fitcount-v1"),
                Map.entry("prior_version", RolloutPrior.VERSION),
                Map.entry("prior_digest", prior.digest()),
                Map.entry("prior_unsupported", prior.unsupportedReason()),
                Map.entry("forecast", "saturation-iid-whole-bundle"),
                Map.entry("candidates", Integer.toString(candidateLimit)),
                Map.entry("scenarios", Integer.toString(scenarioCount)),
                Map.entry("horizon", Integer.toString(horizon)),
                Map.entry("copy_cap", Integer.toString(cap)),
                Map.entry("cache_rows", Integer.toString(cacheRows)),
                Map.entry("workers", "1"),
                Map.entry("seed_derivation", "splitmix64-rollout-v1"),
                Map.entry("candidate_seed_xor", "a0761d6478bd642f"));
    }

    @Override
    public Map<String, Long> counters() {
        final var values = new LinkedHashMap<String, Long>();
        values.put("attempts", attempts);
        values.put("declines", declines);
        values.put("evaluated_decisions", (long) decisions);
        values.put("fallback.prior-unsupported", unsupported);
        values.put("baseline_wins", baselineWins);
        values.put("candidate_scenario_branches", branches);
        values.put("continuation_calls", continuationCalls);
        values.put("continuation_admitted", continuationAdmitted);
        for (int decision = 0; decision < decisions; decision++) {
            for (int scenario = 0; scenario < scenarioCount; scenario++) {
                values.put("path." + tracedAttempts[decision] + "." + scenario + ".creates",
                        (long) pathLengths[decision * scenarioCount + scenario]);
            }
        }
        return values;
    }

    private void reserveTrace() {
        final long paths = (decisions + 1L) * scenarioCount;
        if (paths > Integer.MAX_VALUE - 8L || paths > Runtime.getRuntime().maxMemory() / 512) {
            throw new IllegalStateException("Rollout path trace exceeds the configured heap budget");
        }
        if (tracedAttempts.length <= decisions) {
            final long limit = Math.min(Integer.MAX_VALUE - 8L, Runtime.getRuntime().maxMemory() / (512L * scenarioCount));
            final int count = (int) Math.min(limit, Math.max(8L, 2L * tracedAttempts.length));
            tracedAttempts = Arrays.copyOf(tracedAttempts, Math.max(decisions + 1, count));
        }
        if (pathLengths.length < paths) {
            final long limit = Math.min(Integer.MAX_VALUE - 8L, Runtime.getRuntime().maxMemory() / 512);
            pathLengths = Arrays.copyOf(pathLengths, (int) Math.min(limit, Math.max(paths, 2L * pathLengths.length)));
        }
    }

    private static long mix64(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
