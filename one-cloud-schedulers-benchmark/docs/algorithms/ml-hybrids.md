# Дополнительное ТЗ: два ML-гибрида

Статус: planned. Это **дополнение к трём алгоритмам без ML**, а не замена
одного из них. База: `2051ebb`. Прочитать [основное ТЗ](hybrids.md),
[правила репозитория](../../../AGENTS.md), [RL-контракт](rl.md).

| ID | Candidate selector | Refiner |
| --- | --- | --- |
| `rl-pool-fitcount` | Random20 + до 8 PPO proposals из pool≤64 | Обычный physical lex FitCount |
| `rl-masked-fitcount` | Тот же selector, те же features/RNG/gates | Static-mask lex из masked-fitcount |

Оба используют existing PPO 115→tanh32→1, без новых весов/feature schema,
обучения и dependencies. Runtime source — тот же packaged/custom policy artifact.
[Текущий отчёт](rl.md#полный-dataset-08) не показывает победы PPO над Random20;
ограничение inference и сочетание с masks — новые гипотезы, требующие проверки.

Общие constraints, seed44/fitCap10/cacheRows64, catalogue32, read-only plans,
RandomN sampler, lifecycle и оценка ресурсов заданы в
[основном ТЗ](hybrids.md#границы-и-общий-профиль). Здесь оба array overload
последовательно вызывают single-request scheduling, без trials/window search.

## rl-pool-fitcount

### Параметры

| Параметр | Default | Допустимые значения |
| --- | ---: | --- |
| `topN` (базовая random квота r) | 20 | существующие 1..1000 |
| `poolSize` | 64 | 1..4096; не меньше topN |
| `extraCandidates` | 8 | 0..1000; не больше poolSize−topN |
| `model` | пустая строка | встроенная PPO; иначе JSON path как в текущем RL |

Переиспользовать `PolicyNetwork`, `RlFeatures` и формат модели без изменения
feature schema. Для базового checkpoint на `2051ebb` SHA256 `policy.json`:
`3ad2c2f63dedeef22e76f815770c7400b108a8b244b90245bbe216b45630c482`.
Runtime всегда записывает SHA реально загруженных bytes; custom model допустима
при обычной валидации версии/features/weights. Число — источник эксперимента,
не allowlist и не обещание качества.

### Алгоритм и RNG

1. Построить R тем же sampler/stream, что RandomN с `topN=r`. Только `C<=r`
   позволяет вернуть все планы **до** random draws.
2. При `extraCandidates=0` вернуть R. Если `C−r<=extraCandidates`, добавить весь
   complement и вернуть union без inference и auxiliary draws. В частности,
   для default `21<=C<=28` сначала расходуются ровно 20 baseline draws.
3. Вычислить `q=min(poolSize−r,C−r)` и `inferenceNeeded=(q>extraCandidates)`.
   До auxiliary draws и prepare применить row gate, точно заданный ниже.
   `Q` состоит только из индексов вне R. Если complement помещается, взять
   его целиком без auxiliary draws. Иначе выполнить q шагов того же partial
   Fisher–Yates по compact buffer, оставшемуся после R, с `remaining=C−r`.
   Auxiliary RNG один на весь lifetime:
   `SplittableRandom(seed ^ 0xBB67AE8584CAA73BL)`. Не пересоздавать его на запрос.
   Отсортировать Q по исходному planner index.
4. Если `q<=extraCandidates`, добавить Q без inference. Иначе получить features
   и выбрать лучшие `extraCandidates` **из Q**, через существующий
   `PolicyNetwork.shortlist(rows, request.size(), extraCandidates)`.
   Его tolerance `1e-5*members` и выбор первого canonical index сохранить.
5. Перевести local Q indices обратно в исходные, вернуть sorted `R ∪ selectedQ`
   обычному FitCount. При defaults максимум 28 финальных планов, общий pool
   максимум 64, neural inference максимум 44 планов.

Модель только предлагает варианты. Logit не является Q-value, вероятностью
успешного размещения, confidence или terminal cost. Не смешивать его численно
с FitCount/FGD и не добавлять новый reject action.

### Sparse features: обязательный контракт

`PolicyNetwork` и feature extractor создать **один раз в конструкторе**.
`CandidateFeatures.initialHost/initialTotal` сохраняют baseline начального
canonical context; не пересоздавать их на каждом запросе/подмножестве.

Нужен узкий sparse overload `RlFeatures.extract(request, fullPlans, C, indices)`
или равнозначный внутренний метод. Подготовить общие Decision statistics один
раз через `prepare(request, fullPlans, C)`, извлечь только absolute member-row
диапазоны выбранных canonical indices, batch не более 1024 rows. Не подавать
сжатый массив Q как будто это весь feasible universe: `candidate_count`
обязан оставаться **C**, а все 115 значений выбранной строки — точно совпадать
с соответствующей строкой полного extraction на том же состоянии.

Количество inference rows — `q*request.size()`, а не q. Константа первого
релиза **MAX_FEATURE_ROWS=16384**. После R, до auxiliary draws, prepare и feature
allocations, если `inferenceNeeded`, вычислить checked long произведения:

```text
sparseRows = (long)q * request.size()
fullRows = (long)C * request.size()
gated = sparseRows > 16384 OR fullRows > Integer.MAX_VALUE
```

При gated вернуть R и увеличить `inference_row_budget`, не расходуя auxiliary
RNG. Первый предел относится только к выделяемым sparse rows. Absolute index
выше 16384 допустим, если fullRows помещается в существующий int row API.
Когда Q добавляется целиком и inference не нужен, gate не применять.

Глобальные feature statistics по-прежнему обходят hosts × 11 dimensions.
Ограничение neural rows не означает `O(64)` total scheduling cost. Не создавать
полную `C*members` feature matrix ради выбранных rows и не кэшировать dynamic
features между решениями. Сеть может не различать разную device fragmentation
при равных total/max/whole-free GPU summaries.

Модель загрузить и валидировать даже при последующих inference fast paths.
Ошибки path/JSON/schema/non-finite features/score прекращают run; не маскировать
их ordinary random fallback. Сохранять `model_sha256`, actual pool limits и
row gate в параметрах результата, чтобы отличать запуски.

Счётчики: `auxiliary_pool_plans`, `ml_proposed_plans`, `ml_scored_plans`,
`ml_feature_rows`, `inference_calls`, `inference_row_budget`, общие candidate
counters. Rows считаются member rows; `ml_proposed_plans` учитывает только
предложения после реального inference. Actual extra count, включая fast paths
без inference, и retained hosts записываются отдельно.

## rl-masked-fitcount

### Композиция и параметры

Параметры в точности как у rl-pool-fitcount: topN20, poolSize64,
extraCandidates8, model="", seed44, fitCap10, cacheRows64, с теми же ranges.
Selector и sparse features — **общая реализация** с ML1, а refiner — **тот же**
masked logical-profile order/cache из classical masked-fitcount.

Один реальный FitCount delegate, один physical cache и один canonical commit.
Не запускать сначала один scheduler, потом второй. Learned logits не складывать
с mask scarcity и не использовать как калиброванную value/confidence.

Вместимость masked runtime scratch резервировать для максимального финального
shortlist `topN+extraCandidates`, default28, а не только для random topN20.
Все static mask budgets/ALL/EMPTY/lazy projection и signed losses сохраняются.

| Mask capability | ML row gate | Реальный путь |
| --- | --- | --- |
| ON | не сработал | RL pool → masked LEX |
| OFF: profile/workspace limit | не сработал | Тот же RL pool → physical LEX; соответствует rl-pool-fitcount |
| ON | сработал | R20 → masked LEX; соответствует classical masked-fitcount на этом решении |
| OFF | сработал | R20 → physical LEX |

Mask gate — lifetime выбор refiner. Он не должен сбрасывать RL-предложения
или подменять селектор другой Random20 policy. Row gate — per-decision выбор
предложений, masked refiner при этом остаётся активен. Ошибки модели/features
fail run даже при mask-disabled режиме или extraCandidates=0.

Обязательные точные identities на одинаковых остальных параметрах:

- extraCandidates=0 → classical masked-fitcount, включая checksum/deletes;
- все E=ALL → rl-pool-fitcount;
- mask-disabled → rl-pool-fitcount, включая row gate и callbacks;
- row-gated решение → тот же R20 со своим masked/physical refiner.

Мотивация: PPO ранжирует по ограниченным state features, в которых нет точных E;
static-mask scarcity отдельно бережёт места для узких допустимых множеств.
Это не новая обученная модель и не доказательство улучшения всех ресурсов.

## Карта реализации

Классы `rl/RlPoolFitCount` и `rl/RlMaskedFitCount`, две definitions в Algorithms.
Допустим один небольшой package-private RlPoolSelector для общего sampling,
sparse extraction и модели. Оба adapters получают один refiner: physical или
masked. Между пакетами rl и fitcount дать один узкий осознанный constructor/factory
для MaskedFitCount с CandidateSelector и declared maximum retained width;
внутренний ranking seam остаётся внутри fitcount. Не открывать mutable debit,
не дублировать cache/validator и не строить provider framework.

Сохранить старые RlAlgorithm/RlTopNFitCount и их direct all-candidate semantics.
Старый private final delegate не является поводом копировать всю реализацию.
Инициализировать canonical features один раз; callbacks/close передавать единому
delegate. Record runtime model SHA, pool parameters, mask-enabled state и
раздельные причины gates; inference/feature/scoring входят в operation timing.

## Проверки и paired experiments

Использовать существующие PolicyNetworkTest/FitCountTest/EngineTest/RunnerCliTest
и TestModel; отдельные классы на каждый случай не создавать.

- R-only ML1 повторяет RandomN; R-only ML2 повторяет masked-fitcount. C=21,28,>64
  проверяет baseline RNG. Дополнительные RNG живут весь lifetime.
- Sparse/full parity всех115features, canonical C, initialHost baselines после
  placements/deletes/recreates, multi-member/GPU и wide/batched requests.
- q*members row gate и C*members int gate работают до aux/prepare/allocations;
  высокие абсолютные индексы сами по себе не запрещены лимитом16384.
- Constant/tied policy сохраняет network tolerance и canonical ordering;
  выбираются лучшие extras из complement, даже если R имел бы самые высокие
  scores. Sorted union/read-only arrays и callback isolation обязательны.
- ML2 identities/gate table проверяются через eviction/deletes, а masked
  scratch reserve — по final width28 (и другим разрешённым квотам).
- Invalid model/non-finite scores fail; никаких тихих heuristic fallback.

Общий protocol seeds42/44/46, B1/B100 отдельно, 2forks/1warmup/2measurements,
full traces/occupied checkpoints и ресурсные векторы — из
[основного ТЗ](hybrids.md#матрица-эксперимента). Учесть исходные PPO cloud splits;
dataset-08 уже использовался и не является новым нетронутым holdout.
Не смешивать старые таблицы с разными dataset/seed как paired comparison.

Обязательная панель 2×3:

| Refiner | Proposer1 | Proposer2 | Proposer3 |
| --- | --- | --- | --- |
| physical LEX | R20 | R20+uniform8 из того же pool64 | R20+PPO8 из pool64 (ML1) |
| masked LEX | R20 | R20+uniform8 из того же pool64 | R20+PPO8 из pool64 (ML2) |

Uniform-extra control применяет **тот же learned-path row gate**. На gated
решениях вернуть R без Q/control draws, хотя inference отсутствует. После
идентичных R/Q draws sample extra8 из Q тем же partial Fisher–Yates, отдельным
persistent `SplittableRandom(seed ^ 0x510E527FADE682D1L)`. Fast paths совпадают.
Это отделяет пользу learned ranking от random pool/budget и mask criterion.

Дополнительно controls: ordinary FitCount, Random28, existing rl-top1 и
rl-topn-fitcount topN20. Shared same-state probes отделяют refiner/selector;
полные trajectories после разных решений закономерно расходятся. Не обещать
одинаковых subsequent pools при разных текущих состояниях.

В отчёт включить exact feature/inference work, actual pool/retained width,
ML-only winner frequency, mask-enabled coverage, каждый logical resource vector,
отказы и p50/p95/p99. Не приписывать inference fast path или physical fallback
новому ML/mask качеству. Перед завершением — minimizer, независимый review,
исправление P0–P2, git diff --check и module build, как в основном ТЗ.
