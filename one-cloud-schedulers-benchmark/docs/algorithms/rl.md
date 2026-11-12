# RL placement

Два алгоритма используют одну встроенную PPO-политику:

| ID | Как выбирает размещение |
| --- | --- |
| `rl-top1` | Нейросеть выбирает один допустимый план. |
| `rl-topn-fitcount` | Нейросеть оставляет Top-N, FitCount выбирает среди них. |

```sh
./gradlew build installDist
./placement-bench run --dataset /path/to/dataset --algorithm rl-top1
./placement-bench run --dataset /path/to/dataset --algorithm rl-topn-fitcount --param topN=20
./placement-bench algorithms rl-topn-fitcount
```

Оба доступны в симуляторе. Python нужен только для переобучения; готовая модель
выполняется на Java и входит в дистрибутив. Собственный JSON можно передать
через `--param model=/path/to/policy.json`. Пустой `model` выбирает встроенный.

У Top-1 только параметр `model`. У Top-N дополнительно `topN`, `seed`, `fitCap`
и `cacheRows` с тем же смыслом, что у RandomN/CatBoost + FitCount. Типы,
описания и defaults формируются общим реестром, доступны в CLI и UI.

## Контракт

Общий `GreedyPlanner` строит допустимые атомарные планы с учётом ресурсов,
NUMA, дисков, GPU, связей, фильтров и pinned-host. Модель оценивает только эти
планы. При отсутствии кандидатов запрос отклоняется; при наличии кандидата
размещается. Уже размещённые pod не перемещаются, удаления выполняет движок.

Сеть оценивает каждого участника запроса; сумма оценок задаёт предпочтение
целого плана. Top-N сохраняет исходный порядок выбранных кандидатов для
FitCount. При `N=1` результат совпадает с Top-1. Если на всех решениях
`N >= число кандидатов`, результат совпадает с FitCount с теми же настройками.

Сеть: 115 признаков → tanh(32) → одно число. Первые 111 признаков общие с
CatBoost, ещё четыре описывают свободные доли и целые свободные GPU-устройства
NVIDIA/Intel. signed log1p — фиксированное преобразование; mean/scale
нормализации оценены только на train. Проверки
формата и имён признаков выполняются при загрузке, SHA модели выводится в
runtime-параметрах результата.

Это политика выбора размещений, обученная по награде за принятые pod.
Она не получает будущие запросы или время удаления и не прогнозирует их явно.

## Обучение и границы результатов

Источник — protobuf-датасеты проекта: snapshots облаков и синтетические операции.
Обучение использует компактные подмножества с 32 исходными хостами и всем их
inventory. Копируются исходные allocation/constraint-поля, сохраняются NUMA,
CPU power, диски, GPU и ограничения; идентификаторы перенумеровываются.
Сохраняются только целые атомарные запросы, наблюдавшиеся на выбранных хостах.
Запросы с незамкнутыми зависимостями исключаются. Исходные операции фильтруются
по сохранённым ресурсам с неизменными временем, поколением и типом события.

| Выборка | Исходные облака | Подмножеств |
| --- | --- | ---: |
| Train | 02, 05, 07, 09 | 8 |
| Validation | 04, 06 | 4 |
| Test | 01, 08, 10 | 3 |

Сначала сеть учится повторять FitCount, затем проходит PPO. Награда — число
размещённых POD-участников, shard и GPU-pod дополнительно считаются отдельно.
Используются конечные эпизоды с `gamma=1`, `lambda=1`. Встроенный checkpoint
выбирается по общему числу размещённых pod на validation среди PPO-итераций
1–12. Imitation-модель остаётся отдельным контролем. Test не используется для
выбора checkpoint.

Подмножества не представляют всё облако: исключение внешних зависимостей и
выбор GPU-хостов меняют состав нагрузки. GPU-топология остаётся синтетической
`devices20`, как в основном движке. Полный dataset-08 служит отдельной проверкой
переноса; он уже использовался в предыдущих исследованиях и не является новым
нетронутым holdout. Результаты не гарантируют превосходства над FitCount.

Полный offline training pipeline в этом checkout отсутствует. Сохранённые
параметры и provenance есть в
[model.json](../../src/main/resources/one/cloud/benchmark/algorithms/rl/model.json);
[ТЗ воспроизведения ниже](#тз-поведения-и-воспроизведения) отделяет эти записи
от недостающих компонентов полного retraining pipeline.

На трёх тестовых подмножествах:

| Облако, 32 хоста | FitCount, pod | RL Top-1, pod | RL Top-20 + FitCount, pod |
| --- | ---: | ---: | ---: |
| 01 | 1091 | 1078 | 1091 |
| 08 | 960 | 960 | 959 |
| 10 | 290 | 289 | 290 |

В сумме Top-1: −14 pod (−0.60%), гибрид: −1 pod (−0.04%) к FitCount.
Преимущества PPO над imitation по общему числу pod здесь нет: обе Top-1
модели приняли 2327 pod. Это ограничение результата, несмотря на применение RL.
[Все размещения и задержки](../experiments/rl/subsets.csv),
[утилизация ресурсов](../experiments/rl/subset-resources.csv).

## Полный dataset-08

459 хостов, 28 620 нормализованных событий, включая удаления; 14 257 попыток
создать pod, 9 763 shard, из pod 158 запрашивают GPU. Каталог — 32 профиля,
`fitCap=10`, `cacheRows=64`, `seed=42`, `devices20`, последовательные запросы.
Для каждого способа: отдельная JVM, один прогрев, два измерения и отдельный
quality replay. Дополнительный replay по типам ресурсов сверён по checksum.

| Способ | Pod | Shard | GPU-pod | Mean, мс | p95, мс |
| --- | ---: | ---: | ---: | ---: | ---: |
| FitCount | 14138 | 9728 | 133 | 4.126 | 16.662 |
| Random20 + FitCount | 14141 | 9741 | 146 | 0.877 | 1.494 |
| RL Top-1 | 14035 | 9739 | 144 | 1.778 | 3.019 |
| RL Top-20 + FitCount | 14064 | 9736 | 141 | 2.061 | 3.472 |

В этом прогоне Top-1 быстрее FitCount в 2.32 раза, но принимает на 103 pod
меньше (−0.73%). Гибрид быстрее в 2.00 раза, потеря — 74 pod (−0.52%).
Random20 оказался быстрее и принял больше pod, чем оба RL-варианта. Эти
алгоритмы доступны для дальнейшего исследования; превосходства RL не показано.
Это один seed и один JVM fork, а не оценка устойчивости на всех облаках.

Mean — медиана средних задержек двух replay; p50/p95/p99 — медианы соответствующих
перцентилей replay, не pooled-распределение. Время включает полный
`Engine.schedule` атомарного запроса, который может содержать pod и shard.
Утилизация записана на занятых checkpoints, до удаления всех ресурсов.

[Таблица со всеми задержками](../experiments/rl/native-08.csv),
[ресурсы на checkpoints](../experiments/rl/native-08-resources.csv),
[конфигурация воспроизведения](../experiments/rl/native-08.example.json).
В конфигурации замените путь к dataset-08 и запустите:

```sh
./placement-bench benchmark \
  --config one-cloud-schedulers-benchmark/docs/experiments/rl/native-08.example.json
```

Без `catalogueLimit` CLI использует полный каталог; это может изменить решения
гибрида и стоимость FitCount. Таблицы выше относятся к явно указанному лимиту 32.


## ТЗ поведения и воспроизведения

Статус: implemented reference behavior для `rl-top1` и `rl-topn-fitcount`.
Это контракт сопровождения/переноса существующих алгоритмов. ТЗ новых
[RL pool/masked вариантов](ml-hybrids.md) описывает отдельные planned IDs;
текущие direct all-candidate алгоритмы не подменять ими.

### Параметры и lifecycle

| ID | CLI параметры | Default |
| --- | --- | --- |
| rl-top1 | только model | пустая строка = packaged policy.json |
| rl-topn-fitcount | model,topN,seed,fitCap,cacheRows | "",20,44,10,64 |

Bounds Top-N: topN1..1000, fitCap1..100, cacheRows1..4096,
seed ±9007199254740991, как в AlgorithmParameters. Train seed42 и seed42
опубликованного эксперимента не менять в CLI default44. Внутренний delegate
Top1 использует seed0/empty catalogue/cap1/cache1 и один retained candidate;
это не доступные пользователю tuning parameters.

Один PolicyNetwork и RlFeatures на lifetime canonical алгоритма, даже если
решения C<=N впоследствии обходят inference. Подмена Path допустима при обычной
валидации модели; SHA реально загруженных bytes выводится в parameters.
Модель immutable, context/features/cache owner-thread. Реальные placed/removed
callbacks передаются единому FitCount delegate; close/lifetime соблюдать по
контракту SchedulingAlgorithm, нового native handle здесь нет.

Ошибки path/JSON/version/names/order/shape/scale/finite values и inference
fail run. Это не основания молча перейти на RandomN или засчитать decline.
При обычном отсутствии допустимого плана decline выполняет общий engine.

### Точный контракт признаков и ранжирования

C — количество готовых полных GreedyPlanner plans, включая разные NUMA одного
хоста. m=request.size() — число POD/SHARD **members**, а не reward или число pod.
Схема [RlFeatures.names()](../../src/main/java/one/cloud/benchmark/algorithms/rl/RlFeatures.java)
содержит111 упорядоченных shared fields CandidateFeatures и ещё4 GPU-поля:
max-free fraction и whole-free count для NVIDIA, затем Intel.

Общий encoder описан в [CatBoost-контракте](catboost.md#feature-and-artifact-compatibility).
Именно feature contract общий, а не предположение singleton-only обучения:
RL subset/protocol сохраняют целые атомарные запросы. PPO получает все115
полей числовыми float, включая request_kind и request_flags, без CatBoost
преобразования request_kind в categorical string. Host capacity/initialTotal
фиксируются через context.available при создании encoder, cluster stats
обновляются на решение. Поле candidate_count=C до shortlist; все members
описывают одно pre-placement state. GPU summaries не являются полной histogram
свободных GPU cards и не кодируют точные masks/affinity/link identities.

Current RlFeatures materializes **все C*m rows ширины115**. Shared extraction
идёт batches<=1024, но это не ограничивает полную матрицу/стоимость inference
1024 строками. Sparse pool из нового ТЗ пока не является поведением этих IDs.

Для каждого feature v:

```text
x = sign(v) * log1p(abs(v))
z = (x - trainMean) / trainScale
memberScore = outputBias + sum(j=0..31, outputWeight[j] * tanh(bias[j] + W[j] dot z))
candidateScore = sum(memberScores в исходном порядке request)
```

Сохранить formatVersion1, точное совпадение feature names/order, 32 hidden rows,
finite vectors/weights/bias и scale>=1e-6 из
[PolicyNetwork](../../src/main/java/one/cloud/benchmark/algorithms/rl/PolicyNetwork.java).
Политика выдаёт ordinal preference/logit, не калиброванную вероятность успешного
admission, Q-value, прогноз lifetime или отдельный critic/value head.

При C<=N все candidates идут в FitCount без inference. Иначе каждый следующий
slot выбирается так: найти max среди ещё не retained scores, выбрать **первый
исходный индекс** со score>=max−1e−5*m, затем повторить. Сортировать retained
indices в canonical planner order перед FitCount. Не заменять это exact-score
sort, softmax confidence или случайным tie-break.

N=1 совпадает с Top1 при том же model/planner/state. Равенство полного replay
обычному FitCount требует N>=C на каждом решении, тех же seed/cap/cache/catalogue
и lifecycle/callbacks. Сохранение feasibility остаётся у общего engine;
политика ранжирует уже допустимые полные планы, не размещает members отдельно.

### Offline reward, checkpoint и пределы воспроизведения

[model.json](../../src/main/resources/one/cloud/benchmark/algorithms/rl/model.json)
фиксирует имя артефакта весов и SHA, split и source/subset hashes,20 imitation epochs,
12 PPO epochs, выбранную epoch1 (максимум validation POD total, earliest tie),
gamma=lambda=1, train seed42 и recorded software versions. Host-sampling seeds,
training seed и runtime policy seed — разные величины.

Reward — один за каждый admitted POD member, включая recreates. SHARD counts
измеряются отдельно и не дают этого reward; GPU-pod входит в POD и дополнительно
считается отдельно. Признаки не включают последующие реальные arrivals/deletes.
Правило train02/05/07/09, validation04/06, test01/08/10 сохранить при воспроизведении;
выбор checkpoint/normalization по test запрещён. Публиковать также imitation
контроль, а не приписывать ему эффект PPO.

Доступный [TrainingBridge](../../src/main/java/one/cloud/benchmark/algorithms/rl/TrainingBridge.java)
передаёт decision rows/members/reward и принимает индекс действия или teacher
choice; решения с C<=1 без selector и terminal flush тоже учитываются reward.
Некорректный index или EOF при ожидании action — ошибка без готового result.
EOF между эпизодами является нормальным завершением протокола.
Он не заменяет отсутствующий Python optimizer и dataset/experiment driver.

Использовать имеющиеся CloudSubset.java и TrainingBridge.java как основу. Для
полного retraining recipe дополнительно нужны driver/config их запуска и
teacher collection, optimizer, learning rate, PPO clipping/entropy, batch/update
schedule, normalization/export и checkpoint trace. Их отсутствующие
значения не выводятся из готовых weights и не заполняются выдуманными defaults.
Metadata — историческая provenance, а не полный runnable recipe. Для новых
weights обязательны trainer/Java parity fixtures и source/config/artifact hashes;
reported historical parity checks не означают наличие runnable trainer здесь.

### Приёмка последующих изменений

Использовать существующие PolicyNetworkTest, TrainingBridgeTest, CloudSubsetTest,
FitCount/Engine/RunnerCli tests и TestModel. Это требования к будущим изменениям,
а не новые benchmark/training results, выполненные ради документа:

- Loader: names/order/shape/scale/finite failures, packaged/custom SHA; точная
  известная formula и tolerance/first-index ties, сумма members.
- Encoder: fixed baseline, canonical C, pre-placement state, wide/batch rows,
  POD+regular shard, NUMA/device/GPU plans, deletes/recreates. Все hard constraints
  и атомарность остаются у engine.
- N1/all-N границы с matching settings, real callbacks и output checksums;
  полная матрица current RL измеряется как таковая, без фиктивного row cap.
- Bridge/reward: валидное действие/teacher, invalid/fractional/out-of-range index,
  action-awaiting EOF/error, нормальный межэпизодный EOF, terminal flush и
  POD/shard/GPU-pod accounting без потерь reward.
- Для переэкспорта: train/validation/test provenance, checkpoint criterion,
  exported Java/trainer scores с явно зафиксированным tolerance и inference parity.
- Полные paired traces с FitCount, Random20 и imitation, фиксированными моделями,
  seeds/catalogue/GPU mode; B1/B100 отдельно. Показывать accepted logical vectors,
  class refusals, occupied checkpoints, initialization и полный operation timing,
  включая feature scans/matrix, inference, shortlist, validation/commit.
