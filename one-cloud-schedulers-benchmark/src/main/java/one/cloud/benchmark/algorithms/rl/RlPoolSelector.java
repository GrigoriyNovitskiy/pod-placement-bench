package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;

import one.cloud.benchmark.algorithms.fitcount.CandidateSampler;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

final class RlPoolSelector implements FitCount.CandidateSelector {
    static final int MAX_FEATURE_ROWS = 16384;
    private final CandidateSampler sampler;
    private final SplittableRandom auxiliary;
    private final SplittableRandom control;
    private final PolicyNetwork network;
    private final RlFeatures features;
    private final int topN;
    private final int poolSize;
    private final int extras;
    private final boolean uniformExtras;
    private long selectorCalls;
    private long candidatePlans;
    private long candidateHosts;
    private long retainedPlans;
    private long retainedHosts;
    private long poolPlans;
    private long extraPlans;
    private long proposedPlans;
    private long scoredPlans;
    private long featureRows;
    private long inferenceCalls;
    private long rowBudget;

    RlPoolSelector(SchedulingContext context, long seed, int topN, int poolSize, int extras,
            Path model, boolean uniformExtras) throws IOException {
        if (topN < 1 || topN > 1000 || poolSize < topN || poolSize > 4096
                || extras < 0 || extras > 1000 || extras > poolSize - topN) {
            throw new IllegalArgumentException("Invalid RL pool quotas");
        }
        this.topN = topN;
        this.poolSize = poolSize;
        this.extras = extras;
        this.uniformExtras = uniformExtras;
        sampler = new CandidateSampler(seed);
        auxiliary = new SplittableRandom(seed ^ 0xBB67AE8584CAA73BL);
        control = new SplittableRandom(seed ^ 0x510E527FADE682D1L);
        network = PolicyNetwork.load(model);
        features = new RlFeatures(context);
    }

    @Override
    public int[] select(Request request, Assignment[][] plans, int count) {
        selectorCalls++;
        candidatePlans += count;
        int previousHost = -1;
        for (int index = 0; index < count; index++) {
            final int host = plans[index][0].host();
            if (host != previousHost) {
                candidateHosts++;
                previousHost = host;
            }
        }
        final var random = sampler.sample(count, topN);
        final int[] selected;
        if (count <= topN || extras == 0) {
            selected = random;
        } else {
            final int q = Math.min(poolSize - topN, count - topN);
            final boolean inferenceNeeded = q > extras;
            if (inferenceNeeded && rowGated(count, q, request.size())) {
                rowBudget++;
                selected = random;
            } else {
                final var pool = sampler.sampleRemaining(q, auxiliary);
                poolPlans += pool.length;
                final int[] proposals;
                if (!inferenceNeeded) {
                    proposals = pool;
                } else if (uniformExtras) {
                    proposals = uniform(pool);
                } else {
                    final var local = network.shortlist(features.extract(request, plans, count, pool),
                            request.size(), extras);
                    proposals = new int[local.length];
                    for (int index = 0; index < local.length; index++) {
                        proposals[index] = pool[local[index]];
                    }
                    inferenceCalls++;
                    scoredPlans += q;
                    featureRows += (long) q * request.size();
                    proposedPlans += proposals.length;
                }
                extraPlans += proposals.length;
                selected = Arrays.copyOf(random, random.length + proposals.length);
                System.arraycopy(proposals, 0, selected, random.length, proposals.length);
                Arrays.sort(selected);
            }
        }
        retainedPlans += selected.length;
        previousHost = -1;
        for (final int index : selected) {
            final int host = plans[index][0].host();
            if (host != previousHost) {
                retainedHosts++;
                previousHost = host;
            }
        }
        return selected;
    }

    static boolean rowGated(int count, int q, int members) {
        return (long) q * members > MAX_FEATURE_ROWS || (long) count * members > Integer.MAX_VALUE;
    }

    private int[] uniform(int[] pool) {
        final var selected = new int[extras];
        int remaining = pool.length;
        for (int index = 0; index < selected.length; index++) {
            final int chosen = control.nextInt(remaining);
            selected[index] = pool[chosen];
            pool[chosen] = pool[--remaining];
        }
        Arrays.sort(selected);
        return selected;
    }

    Map<String, String> parameters() {
        return Map.of("model_sha256", network.sha256(), "topN", Integer.toString(topN),
                "poolSize", Integer.toString(poolSize), "extraCandidates", Integer.toString(extras),
                "max_feature_rows", Integer.toString(MAX_FEATURE_ROWS),
                "uniformExtras", Boolean.toString(uniformExtras));
    }

    Map<String, Long> counters() {
        return Map.ofEntries(Map.entry("selector_calls", selectorCalls),
                Map.entry("candidate_plans", candidatePlans), Map.entry("candidate_hosts", candidateHosts),
                Map.entry("retained_plans", retainedPlans), Map.entry("retained_hosts", retainedHosts),
                Map.entry("auxiliary_pool_plans", poolPlans), Map.entry("extra_plans", extraPlans),
                Map.entry("ml_proposed_plans", proposedPlans), Map.entry("ml_scored_plans", scoredPlans),
                Map.entry("ml_feature_rows", featureRows), Map.entry("inference_calls", inferenceCalls),
                Map.entry("inference_row_budget", rowBudget));
    }
}
