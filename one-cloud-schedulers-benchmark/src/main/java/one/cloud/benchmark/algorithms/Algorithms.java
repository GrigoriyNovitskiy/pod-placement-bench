package one.cloud.benchmark.algorithms;

import one.cloud.benchmark.algorithms.catboost.CatBoostRolloutTop1;
import one.cloud.benchmark.algorithms.catboost.CatBoostRolloutTopNFitCount;
import one.cloud.benchmark.algorithms.catboost.CatBoostTop1;
import one.cloud.benchmark.algorithms.catboost.CatBoostTopNFitCount;
import one.cloud.benchmark.algorithms.fgd.Fgd;
import one.cloud.benchmark.algorithms.fitcount.BulkFitCount;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fitcount.RandomNFitCount;
import one.cloud.benchmark.algorithms.fitcount.Rollout;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.algorithms.ilp.ProactiveIlp;
import one.cloud.benchmark.algorithms.ilp.ReactiveIlp;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring;
import one.cloud.benchmark.algorithms.kubernetes.KubernetesResourceScoring.Strategy;

import static one.cloud.benchmark.algorithms.AlgorithmRegistry.register;

final class Algorithms {
    private Algorithms() {
    }

    static void registerAll() {
        register(FitCount.definition());
        register(FitCount.weightedDefinition());
        register(BulkFitCount.definition());
        register(BulkFitCount.dynamicProgrammingDefinition());
        register(Rollout.definition());
        register(GeometricBestFit.definition());
        register(ReactiveIlp.definition());
        register(ProactiveIlp.definition());
        register(Fgd.definition());
        register(Fgd.cpuRamDefinition());
        register(KubernetesResourceScoring.definition(Strategy.LEAST_ALLOCATED));
        register(KubernetesResourceScoring.definition(Strategy.MOST_ALLOCATED));
        register(KubernetesResourceScoring.definition(Strategy.REQUESTED_TO_CAPACITY_RATIO));
        register(KubernetesResourceScoring.definition(Strategy.BALANCED_ALLOCATION));
        register(CatBoostTop1.definition());
        register(CatBoostTopNFitCount.definition());
        register(CatBoostRolloutTop1.definition());
        register(CatBoostRolloutTopNFitCount.definition());
        register(RandomNFitCount.definition());
    }
}
