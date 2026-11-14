# ТЗ: три гибридных алгоритма без ML

Статус: реализовано; алгоритмы зарегистрированы в общей CLI и simulator registry.
База исходников: `2051ebb`. Прочитать [правила репозитория](../../../AGENTS.md),
[общий контракт](README.md#shared-contract), [FitCount](../../FITCOUNT.md)
и соседние реализации. Это **основная тройка без ML**. Два дополнительных
варианта с существующей PPO описаны отдельно в [ML-ТЗ](ml-hybrids.md).

| ID | Механизм | Scope |
| --- | --- | --- |
| `portfolio-fitcount` | Random20 ∪ fragmentation4 ∪ LeastAllocated4 → обычный FitCount, до 28 планов | B=1; B>1 последовательно |
| `masked-fitcount` | Random20 → lexicographic FitCount со static masks/pins | B=1; B>1 последовательно |
| `window-portfolio-fitcount` | Два полных плана: Random20 и portfolio; заменить baseline только при strict admission-set superset | Явно объявленные окна 2..100; B=1 — Random20 |

Конкретные квоты — стартовые проверяемые гипотезы, а не уже измеренные
победители. Отказы, CPU/RAM/GPU/storage и latency оцениваются по каждому случаю;
единого scalar utilization score нет.

## Границы и общий профиль

Только новые размещения. Уже размещённые units не переселять; preallocation,
прогноз удалений, скрытый suffix лога и новые solver dependencies в эту задачу
не входят. Первые два алгоритма используют последовательную реализацию
`schedule(Request[])` из `SchedulingAlgorithm`. Третий сравнивает полные планы
только явно объявленного окна; порядок его запросов сохраняется.

Сохранить атомарность каждого `Request`, включая pod с regular shard. Общий
`GreedyPlanner` и engine проверяют layouts, stripes, whole-disk, metadata и IOPS;
NUMA, GPU и resource classes/dedicated resources; masks, pins, affinity,
anti-affinity, topology, dependencies и regular/shared storage. Отказ текущего
жадного генератора не доказывает отсутствия любого возможного размещения.
Пулы ниже состоят из уже построенных им same-host планов.

| Общий параметр | Default / границы |
| --- | --- |
| `seed` | 44; существующий `AlgorithmParameters.SEED` |
| `fitCap` | 10; существующий диапазон 1..100 |
| `cacheRows` | 64; существующий диапазон 1..4096 |
| GPU model первого сравнения | `devices20` |
| Потоки внутри алгоритма | 1 |
| Catalogue первого сравнения | `catalogueLimit=32`, опция runner, не алгоритма |

Эти defaults относятся к новым ID. Старые defaults и диапазоны не менять.
Профиль 128 из старых bulk-планов сюда не переносить: обычный CLI FitCount
сейчас принимает `fitCap` не более 100. Полный catalogue и cap 64/100 проверять
отдельными одинаковыми для всех участников экспериментами.

### Планы, порядок и случайность

`P[0..C)` — исходный массив `Assignment[]`, переданный `FitCount.CandidateSelector`.
Квоты означают **планы**, не distinct hosts. Anchor 0 и разные NUMA могут дать
несколько планов одного хоста; первый релиз сохраняет это поведение RandomN.
Дедупликация — только по исходному индексу, не по host и не по geometry.

Селектор не изменяет plans, members, disks, NUMA или GPU arrays. Возвращает
непустые строго возрастающие исходные индексы, когда `C>0`. Не получает права
проверять будущие catalogue requests через `plan()`/`canPlace()`.

Переиспользовать семплер из `RandomNFitCount`, допустимо выделить один небольшой
package-private helper. Для квоты `r`:

1. Если `C<=r`, вернуть все индексы, не расходуя RNG.
2. Иначе заполнить scratch `indices[i]=i`, `remaining=C`. Повторить `r` раз:
   `j=rng.nextInt(remaining)`, взять `indices[j]`, заменить его
   `indices[--remaining]`. Отсортировать выбранные индексы.
3. RNG живёт весь срок алгоритма: `SplittableRandom(seed ^ 0x6A09E667F3BCC909L)`.
   Другие источники этот stream не используют.

Совпадение с RandomN означает одинаковый random shortlist при одинаковой
последовательности `C` и seed. После разных commit состояния и RNG tie-breaking
FitCount могут разойтись; обещания одинакового полного trace нет.

Для дополнительных cheap scorers использовать точные сравнения конечных score,
без нового epsilon. `-0.0` и `+0.0` считать равными. Tie key:

```text
mix64(z):
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9
    z = (z ^ (z >>> 27)) * 0x94d049bb133111eb
    return z ^ (z >>> 31)
key(i, salt) = mix64(seed ^ mix64(attempt) ^ mix64(i) ^ salt)
```

64-bit wraparound и unsigned shifts/comparison, затем исходный индекс.
`attempt` — zero-based индекс всех реальных `schedule(Request)`, включая
отклонённые и singleton решения. Для fragmentation salt
`0x3C6EF372FE94F82B`, для LeastAllocated `0xA54FF53A5F1D36F1`.
Это не изменяет последний seeded tie-break самого FitCount.

## portfolio-fitcount

### Параметры и шаги

| Параметр | Default | Допустимые значения |
| --- | ---: | --- |
| `randomCandidates` | 20 | 1..64 |
| `fragmentationCandidates` | 4 | 0..16 |
| `leastCandidates` | 4 | 0..16 |

Обозначим эти квоты `r,g,l`. При `C<=r` вернуть все планы сразу. Иначе:

1. Получить `R` указанным выше семплером.
2. Если `g>0`, найти лучшие `min(g,C)` планов по минимальному fragmentation delta.
   При `model.gpus!=null` использовать
   `GreedyPlanner.gpuFragmentationScorer(catalogue)`, в том числе для CPU-only
   текущего запроса: CPU-ресурсы могут блокировать будущие GPU-запросы. При
   `model.gpus==null` использовать `cpuRamFragmentationScorer(catalogue)`.
   В режиме aggregate это явно CPU/RAM-источник, не device-aware GPU score.
   Нынешний GPU scorer моделирует CPU/GPU, а не RAM или storage; последние
   учитываются окончательным FitCount и engine.
3. Если `l>0`, найти лучшие `min(l,C)` планов по максимальному **существующему**
   integer score `Strategy.LEAST_ALLOCATED`. Переиспользовать точную формулу
   resource families и rounding, выделив узкую scorer factory из
   `KubernetesResourceScoring`. Старую реализацию/её host/NUMA selection не менять.
   Здесь score применяется к каждому плану, без схлопывания NUMA по host.
4. Объединить индексы `R ∪ G ∪ L`, отсортировать, передать обычному FitCount.
   Пересечения не добирать другими кандидатами. Реальный размер может быть
   меньше `r+g+l`; это часть определения алгоритма.

Scorers создать один раз, когда соответствующая квота ненулевая. Не вызывать
`Fgd.improveGpus`: оно меняет geometry и не относится к read-only селектору.
Top-K поддерживать маленькими primitive buffers; framework поставщиков/плагинов
или `sort` всего массива объектов ради четырёх элементов не нужен.

Проверяется не только число кандидатов FitCount: оценка всех `C` планов по
fragmentation/LeastAllocated сама может быть дорогой. Общая стоимость — полный
collect + до `C` оценок каждого включённого scorer + FitCount на union.
Никакого обещания sublinear host search нет.

### Наблюдаемость и критерии

Счётчики селектора (вызовы с `C>1`): `selector_calls`, `candidate_plans`,
`retained_plans`, `retained_hosts`,
`fragmentation_scores`, `least_scores`, `shortlist_overlap`,
`fragmentation_cpu_ram_decisions`. Суммировать по вызовам селектора;
`shortlist_overlap = |R|+|G|+|L|−|union|`.

При `g=l=0` полностью воспроизводить RandomN с `topN=r`, включая placements и
checksum через deletes/recreates. Основные сравнения: Random20, Random28,
R20+G4, R20+L4, полный R20+G4+L4. Одинаковый seed/сценарий обязателен.
Добавление вариантов не гарантирует улучшения последующего неизвестного trace.

## masked-fitcount

### Параметры и смысл

Дополнительный CLI-параметр только `topN`, default 20, существующий диапазон
1..1000. Shortlist в точности RandomN с этим `topN`; portfolio-источники здесь
не участвуют. Это отдельная проверка критерия scarcity.

`g` — **существующий** physical-family key FitCountCache, включая полную геометрию
атомарного запроса. Для каждого occurrence в объявленном catalogue получить:

```text
E(h) = AND по всем members:
       model.candidates.allows(memberMask, h)
       AND (pinnedHost == 0 OR pinnedHost == h)
p = (physicalFamily g, canonical E)
f[p] = число catalogue occurrences такого p
ordinal[p] = индекс первого occurrence в catalogue
```

Равенство E определять по содержимому разрешённого множества, не по mask ID.
Разные выражения с одинаковым пересечением и эквивалентные pins объединяются.
Одинаковая геометрия с разными E **не объединяется**. Для ALL/EMPTY использовать
общие значения. Не добавлять в E другой фильтр disabled/availability, которого
нет в baseline physical rows: это меняет обещание эквивалентности ниже.

Это static mask-permitted supply. Readiness, anti-affinity, группы, домены и
зависимости не входят в прогноз E и могут изменяться без capacityRevision хоста.
Для реального текущего запроса их по-прежнему проверяет common engine.

Пусть `F[g,h]` — capped physical copy count общего жадного kernel:

```text
T[p] = sum(h where E[p](h), F[g(p),h])
order = ascending T[p] / (double) f[p], then ordinal[p]
loss[p,a] = E[p](host(a)) ? F[g(p),host(a)] - F[g(p),host(a) after full a] : 0
```

Сравнивать планы лексикографически по loss в этом порядке, меньший лучше;
последний tie-break — прежний FitCount. Здесь каждый готовый план same-host,
но debit включает всех pod/shard members, NUMA, disks и GPU.

Хранить signed losses. Жадный physical kernel может иметь немонотонные curves;
не clamp к нулю. Сохранять zero-total и EMPTY профили в определённом порядке:
при `T=0` нельзя молча считать все marginal losses нулевыми. Это не точный
oracle числа будущих реально допустимых replicas и не глобальный optimizer.

### Кэши, границы и обновления

Physical rows/key и `cacheRows` общие с FitCount. Никаких count-таблиц
`logicalProfile × host`. Глобальный logical-profile order сохранять, например
A(mask1), B(mask1), A(mask2) нельзя переставить в A,A,B. Один physical delta
переиспользовать только в непрерывном блоке профилей одного family; при возврате
к family после другого разрешён повторный расчёт для оставшихся кандидатов.
Допустимы canonical bitsets только для **различных** static intersections,
не отдельные копии для каждого catalogue occurrence.

Отдельно хранить `T`, frequency, ordinal и last-refresh generation логических
профилей. При refresh physical row:

- для изменённого host сначала взять old/new count и одновременно обновить T
  всех логических профилей этого семейства, разрешающих host;
- при cache miss/eviction reload построить row и все T семейства заново с нуля;
- не применять delta к T, потеряв old count. При eviction сохранить исторические
  T/generation для безопасных lower bounds, но не выдавать их за свежие totals.

Переиспользовать lazy scarcity order FitCount на уровне logical profiles:

```text
changes = globalHostChangeGeneration - profileGeneration
lower = changes > T/cap ? 0 : T - changes*cap
bound = lower / (double)frequency
```

Обрабатывать pending lower bounds и refreshed ready profiles как в текущем
`FitCountCache.next()`. Refresh семейства обновляет totals его масок согласованно.
Не заменять lazy order обязательным полным пересчётом всех families на каждом
запросе. Для same-state order bound остаётся lower bound, даже если семейство
обновило другие pending profiles. Эпохи и capacity revisions берутся из обычного
canonical engine; trial reuse этим алгоритмом не вводится. Pending bound keys
зафиксированы на текущий beginOrder: refresh меняет T/generation, но не ключ
элемента, уже находящегося в heap; иначе heap потребовал бы восстановления.

Цена холодного reload — physical work + `O(H*maskProfilesInFamily)` membership
checks; тёплого — `O(changedHosts*maskProfilesInFamily)`. Это учитывать в timing.
Непрерывный dense `afterCount[candidate][family]` также не создавать: временный
вектор на последний family и shortlist достаточен. При фильтрации candidates
компактировать его вместе с retained plans; при смене family инвалидировать.

Лимиты дополнительной компиляции masks/order: **4096 logical profiles** и
**64 MiB primitive-array payload**, константы первой реализации. Перед каждой
аллокацией проверить сумму длин*ширин через checked long arithmetic; учитывать
retained и временные arrays, classes/order/heap storage, рабочие mask words.
Это не обещание ограничения total JVM heap: Map/object overhead измеряется
отдельно. Existing physical cache/model в эти дополнительные 64 MiB не входят.
На инициализации зарезервировать верхнюю границу runtime scratch по H, числу
профилей и topN, включая временное сосуществование старого/нового buffers.
После включения masked mode workspace не растёт за этот резерв. Если такой
резерв не помещается, выбрать lifetime fallback ещё до первого schedule.

При превышении любого лимита при инициализации освободить частично построенную
надстройку и на весь lifetime использовать обычный physical RandomN+FitCount с
теми же seed/topN/cap/cache. Нельзя усекать catalogue, маски или order ради лимита.
Вывести причину `profile_limit`/`workspace_limit`, `masked_enabled=false` и
фактические ограничения; это заранее объявленный fallback, не masked result.
OOM, malformed model, cancel и неожиданные исключения таким fallback не являются.

При всех E=ALL алгоритм должен точно повторять RandomN+обычный FitCount, включая
cache eviction и deletes. Это важнее оптимизации новой ветки на первом шаге.
Не включать одновременно best-fit tie-break или inverse-supply weighted sum.

Счётчики: `logical_profiles`, `physical_families`, `distinct_masks`,
`empty_mask_profiles`, `masked_row_rebuilds`, `masked_host_deltas`, `masked_family_refreshes`,
`masked_membership_checks`, `masked_workspace_payload_peak_bytes`,
`masked_profile_limit`, `masked_workspace_limit`; общие candidate counters.

## window-portfolio-fitcount

### Параметры и цель

Это ограниченный портфель **двух полных trajectories** известного окна,
а не stochastic rollout или поиск по скрытому будущему. Он не требует
interchangeability реплик и подходит в том числе для неоднородных окон,
на которых bulk/DP отклоняют структурную сертификацию.

| Параметр | Значение |
| --- | --- |
| `seed` / `fitCap` / `cacheRows` | 44 / 10 / 64, общие CLI descriptors |
| `comparePortfolio` | true; единственный дополнительный CLI bool |
| Размер оптимизируемого окна | 2..100 atomic requests, фиксированный limit |
| Incumbent | RandomN+FitCount с topN=20 |
| Challenger | portfolio-fitcount с квотами 20/4/4 |
| Число trajectories | максимум 2; без beam/pivot/synthetic continuation |
| Дополнительный snapshot budget | 64 MiB по формуле ниже |

Получить полный baseline. Пусть S0 — множество **индексов запросов этого окна**,
принятых baseline, а S1 — множество challenger. Выбрать challenger тогда и
только тогда, когда `S0 proper-subset S1`. При равенстве, несравнимости или
потере хотя бы одного baseline request сохранить **точные планы** incumbent.
Большее количество принятых requests само по себе недостаточно.

Это сохраняет каждый уже принятый baseline atomic request и его pod/shard
members, поэтому не жертвует их logical resource demand ради другого типа
ресурса на этом окне. Гарантия только для того же начального состояния,
окна и определённого ниже window-seeded baseline. Она не относится к legacy
persistent Random20, следующим неизвестным запросам или latency. Меньшая
физическая фрагментация при одинаковом S не является новым tie-break.

### Seed и обычные пути

Adapter содержит отдельный persistent canonical RandomN20 для singleton и
fallback. Он получает все реальные `capacityChanged(host)` callbacks ровно один раз;
trial callbacks до него не доходят. Его seed — обычный run seed.

`w` — zero-based номер каждого **непустого** вызова с объявленными create
requests, включая singleton и fallback. В `schedule(Request)` и
`schedule(Request[])` один логический вызов увеличивает w только один раз.
Empty array ничего не делает. При array длиной 1 не вызывать собственную
single overload с повторным increment; обратиться сразу к canonical fallback.

Для окна 2..100:

```text
windowSeed = mix64(runSeed ^ mix64(w))
```

Использовать mix64 из общего раздела. Оба trial policy конструктора получают
один и тот же windowSeed. Нельзя читать/клонировать приватный RNG persistent
policy или обещать эквивалентность её потоку.

При B=1 обычный Random20 без trial. При B>100 — named `oversized_window`
fallback в persistent Random20. Если только вызов `context.trial(model)`
сообщил отсутствие capability — named `trial_unavailable` fallback.
Fallback не меняет порядок запросов. `comparePortfolio=false` оставляет
тот же window-seeded incumbent, snapshot и validation protocol, отключая лишь
challenger; это обязательный paired control.

### Исполнение и публикация

1. Создать **один** `Engine.Trial` от текущего canonical context с той же
   model. Уже committed placements — константы. Не удалять их ни в одной
   trajectory. Вложенные trials, virtual IDs и будущие delete events не нужны.
2. На `trial.state()` создать fresh RandomN20 с windowSeed и выполнить
   `state.schedule(policy, requests)` для **всего** окна в исходном порядке.
   Закрыть trial policy до canonical publication. Записать все результаты:
   owned `Assignment.copy()` для каждого admitted member и явный null для
   каждого отказа. Полный план включает и отказы; он не означает, что все B
   запросов поместились. Сохранить S0 и baseline snapshots вне mutable trial.
3. Если S0 содержит всё окно или `comparePortfolio=false`, challenger не нужен.
   Иначе `trial.reset()`, создать fresh portfolio policy с тем же windowSeed
   и выполнить всё окно. Свежими должны быть policy/RNG/кэши, а не только
   revision: reset возвращает номера revisions к исходным. Снять полный
   owned snapshot, после чего сравнить S1 только с **исходным S0**.
4. Выбранную sequence полностью проверить ещё одним isolated prefix replay
   после reset. Маленький внутренний ReplayPolicy публикует сохранённые планы
   без перепланирования и сохраняет null-отказы. Каждый prefix проходит common
   validation/accounting, в исходном порядке. Некорректный replay — ошибка run,
   не ordinary decline и не тихий fallback.
5. Завершить replay, закрыть все trial policies, вызвать `trial.reset()` и
   перестать использовать trial **до первого canonical context.place**.
   Затем опубликовать сохранённые admitted plans ровно один раз в исходном
   порядке. Не помещать reset в finally, охватывающий canonical commit:
   изменение source revision сделало бы trial stale.

Частичную challenger trajectory нельзя выбирать. Model errors, cancel,
unexpected exceptions и stale trial fail run. Cleanup при отказе budget/gate
должен закончиться до запуска canonical fallback или публикации incumbent.

### Память, стоимость и счётчики

Перед deep copy считать snapshot estimate как в existing
`BulkFitCount.snapshot/bytes`, через checked long arithmetic:

```text
outer = 16 + 8*B
admittedRequest = 16 + 8*request.size()
member = 112 + 4*(diskOffsets.length + devices.length + gpus.length)
estimate = outer + sum(admittedRequest + sum(member))
```

Это консервативная accounting-формула existing code, не точный object-size/RSS.
Лимит 64 MiB применяется к **одновременно удерживаемым** incumbent и challenger
owned snapshots плюс их admission-index buffers. Проверять до каждой copy.
Сам Engine.Trial, его ledger/counters и policy caches в лимит не входят;
их allocation, heap и время обязательно измерять отдельно в общей операции.

Если incumbent snapshot не помещается, reset/cleanup и named
`incumbent_workspace` fallback в persistent Random20, без canonical commit.
Если incumbent сохранён, а challenger snapshot не помещается, named
`challenger_workspace`: отбросить challenger, сохранить полный incumbent.
Нельзя ловить OOM вместо предварительного accounting.

На допустимом окне максимум `2*B` trial policy unit decisions, `B` final replay
validation steps и до `B` canonical commits. При skip/comparePortfolio=false
trial decisions не более B. Это не ограничение миллисекунд: клонирование
engine state, две fresh policy и cold caches могут оказаться дорогими.
В timing входят snapshot/reset, все constructions, trajectories, prefix replay,
validation и commit. Однократные context-dependent кэши другого решения через
reset не переносить.

Параметры результата: window seed rule, maxWindow=100, maxTrajectories=2,
comparePortfolio, snapshotBudgetBytes и оба policy profiles. Счётчики:
`nonempty_windows`, `trial_windows`, `trial_trajectories`, `trial_unit_decisions`,
`challenger_skipped_full`, `challenger_superset_wins`, `challenger_rejected`,
`prefix_replay_steps`, `snapshot_payload_peak_bytes`, named gate reasons.
`plannerCalls` включает canonical fallback и все trial planning calls.

## Карта реализации и стиль

- Новые классы: `fitcount/PortfolioFitCount`, `fitcount/MaskedFitCount`,
  `fitcount/WindowPortfolioFitCount`. Зарегистрировать три definitions в
  `Algorithms`, использовать существующие typed Parameters. Пока это planned IDs.
- Для первых двух переиспользовать `FitCount.CandidateSelector`, collect/commit
  и физический kernel. Для masked ranking допустим узкий seam в FitCount и
  coherent projections refresh в FitCountCache. Не копировать весь FitCount
  и не создавать вторую ledger/validator/cache authority.
- Window adapter использует existing `Engine.Trial`, RandomN20 и portfolio,
  owned Assignment.copy snapshots и один небольшой ReplayPolicy. Защищённый
  baseline и optional trajectory должны оставаться простым последовательным
  кодом, без дерева solver-плагинов и универсального policy framework.
- Вынести только необходимую scorer factory LeastAllocated и sampler reuse.
  Сохранить public constructors, defaults, protobuf и поведение старых алгоритмов.
- Все callbacks и close передаются соответствующему delegate ровно один раз.
  Canonical и trial policies имеют отдельные lifetime/RNG/caches; scratch owner-thread.
- Java 25, стиль соседнего кода/PersistentQueueTrie; комментарии для неочевидных
  инвариантов. Тесты добавлять в существующие классы с TestModel. Отдельный
  WindowPortfolioTest допустим для самостоятельного оконного контракта,
  а не отдельный класс на каждый edge case.

## Проверки и приёмка

Обязательные содержательные случаи:

1. `g=l=0` portfolio полностью повторяет RandomN с r; C=21,28,>64 ловит сдвиг
   baseline RNG. Selector indices sorted/unique, пересечения не добираются,
   member/device/NUMA/GPU arrays не мутируются.
2. Shared fixture с atomic pod+regular shard, masks/pins, NUMA/devices,
   deletes/recreates и topology/dependency constraints. Отказ не публикует
   часть request, committed старые placements сохраняются.
3. Masked: E=ALL повторяет physical ranking через eviction/deletes; одинаковая
   geometry с E={h1} и E={h1,h2} не сливается и может защитить h1. Эквивалентные
   intersections/pins объединяются. EMPTY/zero-T/negative loss сохраняются.
   Host refresh и eviction reload сверяются с независимым полным подсчётом T.
   Domain readiness без capacity revision не превращает static proxy в
   authoritative feasibility.
4. Mask workspace/profile gates дают полный physical fallback, без truncated
   order. Reserved runtime scratch не растёт за initial bound.
5. Window: strict-superset challenger выбирается; equal/incomparable и даже
   larger-cardinality set с потерей baseline GPU/pod/shard request отклоняется.
   При полном S0 challenger не вызывается. Owned snapshots переживают reset;
   prefix replay, no-trial/B1/oversize/budget paths, w и comparePortfolio=false
   control проверяются. Не должно быть canonical callbacks/state changes до
   выбора/cleanup; final invalid replay fail run. Trial revision alias не
   переносит RNG/cache между trajectories; commit не обращается к stale trial.
6. CLI/typed parameters/diagnostics, reproducibility, callbacks/close и
   cache-row eviction. Eviction при одинаковом остальном меняет затраты,
   а не решения. Model/runtime errors не маскируются gate fallback.

### Матрица эксперимента

Сначала smoke prefix128: один fork, warmups=1, measurements=1, seed44,
probeCap10, inflationAttempts0. Он проверяет исполнение, не fragmentation.
Затем полные traces с churn и occupied checkpoints:

| Настройка | Значение |
| --- | --- |
| B | 1 и 100, отдельные сравнения; window mode только на объявленных windows |
| seeds | 42,44,46 |
| forks / warmups / measurements | 2 / 1 / 2 |
| catalogueLimit / fitCap / cacheRows | 32 / 10 / 64 |
| probeCap / inflationAttempts / inflationSeed | 10 / 1000 / 42 |
| timeoutSeconds | 1800 на worker; timeout — error, не decline |

Freeze dataset hashes, scenario/order digest, resolved parameters, Java/OS/arch,
heap и одного последовательного worker. Начать с малого 459-host и доступных
среднего/большого облаков; tuning/evaluation split фиксировать manifest-ом.
Старые таблицы с разными dataset/seed не склеивать в одно сравнение.

Controls: ordinary FitCount, Random20/28, portfolio R20+G4/R20+L4, masked против
physical с одинаковым R20, window comparePortfolio=true/false с одинаковым
window-seed protocol. Для окон дополнительно показывать обычный persistent
Random20, но локальную superset гарантию ему не приписывать. B1 window mode
должен точно повторять persistent Random20 и не создавать trial.

Host-diverse sampling, best-fit tie-break, weighted f/T и after-debit memo —
отдельные оси. Full catalogue, cap64/100 и cacheRows256 менять по одной оси
с paired controls. При mask memory gate результат подписывать physical fallback.

Отчёт: admitted units/POD/shard/GPU-pod, каждый accepted logical resource vector,
refusals по allocation class, occupied utilization/inflation, p50/p95/p99 полного
Engine.schedule, replay time, scorer/cache/trial work, payload/heap и доля
реально optimized окон. B100 latency относится к окну, B1 — к atomic request.
Ledger utilization включает unavailable capacity; independent capped physical
hole probes не складывать в совместный free capacity. Размены ресурсов
рассматривать по каждому случаю, без единого scalar utilization leaderboard.

Minimizer по полному diff, затем независимый Java/performance/correctness review;
исправлять P0–P2 и повторять проверки. Финально git diff --check и
`./gradlew :one-cloud-schedulers-benchmark:build`. Эффект подтверждать парными
измерениями; raw results и причины fallback сохранять.

## Литература и границы переноса

- [Borg, EuroSys 2015, §3.4](https://pages.cs.wisc.edu/~shivaram/cs744-readings/borg.pdf):
  caching/equivalence/sampling. Здесь selectors сохраняют полный collect;
  candidate-first sampling — отдельный эксперимент.
- [FGD, ATC 2023, §3.2/4.2/7](https://www.usenix.org/system/files/atc23-weng.pdf):
  workload-aware marginal fragmentation и best-fit fallback. Это источник
  scorer, не доказательство для наших masks/NUMA/disks.
- [Tetris, SIGCOMM 2014, §3.2](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/tetris_sigcomm14.pdf):
  resource alignment; его выбор task→machine не заменяет наш feasibility.
- [Online stochastic packing, §2](https://arxiv.org/pdf/1001.5076): цены реальных
  LP constraints. Перекрывающиеся copy-count profiles не дают dual price f/T.
- [Fortified rollout, §4.3](https://www.mit.edu/~jnt/Papers/J066-97-rollout.pdf):
  сохранение полного incumbent. Наш двухполитический window portfolio не
  исследует дерево, не прогнозирует arrivals и защищает только известное окно.

Первые два классических алгоритма не используют trials; третий работает только
с уже объявленным окном. Полноценный pivot/beam/stochastic rollout отложен.
ML не является зависимостью ни одного из этих трёх алгоритмов.

## Запуск

```sh
./placement-bench run --dataset /path/to/sc --algorithm portfolio-fitcount --catalogue-limit 32
./placement-bench run --dataset /path/to/sc --algorithm masked-fitcount --catalogue-limit 32
./placement-bench run --dataset /path/to/sc --algorithm window-portfolio-fitcount --bulk-size 100 --catalogue-limit 32
./placement-bench run --dataset /path/to/sc --algorithm window-portfolio-fitcount --bulk-size 100 --param comparePortfolio=false --catalogue-limit 32
```

Последняя команда — window-seeded incumbent control. B=1 использует persistent
Random20, а B>1 заново создаёт trial policy для каждого объявленного окна.
