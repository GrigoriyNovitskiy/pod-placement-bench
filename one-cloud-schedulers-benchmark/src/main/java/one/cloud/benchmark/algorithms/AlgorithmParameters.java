package one.cloud.benchmark.algorithms;

import java.util.List;

import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;

public final class AlgorithmParameters {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public static final Parameter SEED = Parameter.integer(
            "seed", "Seed", "Начальное значение генератора случайных чисел.",
            44, -MAX_SAFE_INTEGER, MAX_SAFE_INTEGER).asAdvanced();

    public static final Parameter TOP_N = Parameter.integer(
            "topN", "Shortlist size", "Сколько кандидатов передать на окончательный выбор FitCount.",
            20, 1, 1000);

    public static final Parameter FIT_CAP = Parameter.integer(
            "fitCap", "FitCount copy cap", "Максимум копий типового запроса при оценке одного хоста.",
            10, 1, 100).asAdvanced();

    public static final Parameter CACHE_ROWS = Parameter.integer(
            "cacheRows", "FitCount cache rows", "Число профилей запросов в кэше FitCount.",
            64, 1, 4096).asAdvanced();

    public static final Parameter BULK_SEED = Parameter.integer(
            "seed", "Seed", "Начальное значение генератора случайных чисел.",
            42, -MAX_SAFE_INTEGER, MAX_SAFE_INTEGER).asAdvanced();

    public static final Parameter BULK_FIT_CAP = Parameter.integer(
            "fitCap", "FitCount copy cap", "Максимум копий типового запроса при оценке одного хоста.",
            128, 1, Integer.MAX_VALUE).asAdvanced();

    public static final Parameter BULK_CACHE_ROWS = Parameter.integer(
            "cacheRows", "FitCount cache rows", "Число профилей запросов в кэше FitCount.",
            256, 1, 4096).asAdvanced();

    public static final Parameter INCUMBENT_GUARD = Parameter.bool(
            "incumbentGuard", "Sequential quality guard", "Сравнивать пачку с последовательным FitCount.",
            true).asAdvanced();

    public static final Parameter CANDIDATES = Parameter.integer(
            "candidates", "Candidate limit", "Максимум вариантов текущего размещения для rollout.",
            8, 1, Integer.MAX_VALUE);

    public static final Parameter SCENARIOS = Parameter.integer(
            "scenarios", "Continuation paths", "Число будущих потоков для оценки каждого варианта.",
            8, 1, Integer.MAX_VALUE);

    public static final Parameter HORIZON = Parameter.integer(
            "horizon", "Future creates per path", "Число будущих созданий в одном потоке rollout.",
            8, 1, Integer.MAX_VALUE);

    public static final List<Parameter> SEED_ONLY = List.of(SEED);
    public static final List<Parameter> FIT_COUNT = List.of(SEED, FIT_CAP, CACHE_ROWS);
    public static final List<Parameter> SHORTLIST = List.of(TOP_N, SEED, FIT_CAP, CACHE_ROWS);
    public static final List<Parameter> BULK = List.of(BULK_SEED, BULK_FIT_CAP, BULK_CACHE_ROWS, INCUMBENT_GUARD);
    public static final List<Parameter> ROLLOUT = List.of(
            BULK_SEED, BULK_FIT_CAP, BULK_CACHE_ROWS, CANDIDATES, SCENARIOS, HORIZON);

    private AlgorithmParameters() {
    }
}
