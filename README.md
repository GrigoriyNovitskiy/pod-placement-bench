# Placement Bench

Симулятор размещения pod и shard по хостам облака. Можно собрать небольшое облако
вручную или загрузить датасет, пошагово воспроизводить создания и удаления,
сравнивать алгоритмы и смотреть размещения, ресурсы и время обработки.

Веб-интерфейс и CLI используют один Java-движок и общий реестр алгоритмов:
FitCount, Geometric Best Fit, FGD, Kubernetes scoring, CatBoost и RandomN.
Движок проверяет ресурсы, ограничения, NUMA, диски, связи и GPU. Размещённые pod
не мигрируют между хостами.

Это стенд для исследования алгоритмов. Время обработки в интерактивном режиме —
диагностика. Для контролируемых сравнений есть команда `benchmark` с прогревом
и отдельными JVM: [запуск и метрики](one-cloud-schedulers-benchmark/docs/runner/README.md).

RL-политика встроена в `rl-top1` и `rl-topn-fitcount`: [запуск и обучение](one-cloud-schedulers-benchmark/docs/algorithms/rl.md).
Готовые алгоритмы работают на Java без Python и внешнего файла модели.

## Собрать один раз

Нужен **JDK 25**. Если установлено несколько Java, укажите нужную через `JAVA_HOME`.
Gradle для сборки использует toolchain, а готовое приложение — `JAVA_HOME` или Java
из `PATH`. Python, Node.js и отдельная установка Gradle не нужны.

Из корня репозитория:

```sh
./gradlew build installDist
```

Команда запускает тесты и собирает приложение вместе с зависимостями и моделями.
После этого запускайте его через `./placement-bench`: Gradle при каждом запуске
не вызывается. После изменения кода или моделей повторите сборку.

Команды ниже рассчитаны на macOS/Linux. Если `JAVA_HOME` ещё не настроен:

```sh
export JAVA_HOME=/path/to/jdk-25
```

## Запустить симулятор

```sh
./placement-bench simulator
```

Откроется браузер: **http://127.0.0.1:8765**. В начальном примере есть восемь хостов
и запросы с pod, volume и GPU. Своего датасета для первого запуска не требуется.

В интерфейсе можно:

- Добавлять хосты и запросы, планировать атомарное удаление pod вместе с volume.
- Выбирать несколько алгоритмов и обрабатывать одинаковые события во всех копиях облака.
- Выполнять один шаг, десять событий, весь остаток сразу или включать автовоспроизведение.
- Смотреть ресурсы, размещения, ограничения, GPU-доли, метрики и FitCount-пробы.
- Сохранять ручной сценарий в JSON или загружать JSON / protobuf-датасет.

Другой порт и запуск без открытия браузера:

```sh
./placement-bench simulator --port 9000 --no-open
```

Остановка — `Ctrl+C`. Состояние хранится в памяти; ручной сценарий можно сохранить
перед остановкой. Подробнее: [руководство симулятора](one-cloud-schedulers-benchmark/docs/simulator.md).

## Использовать CLI

```sh
./placement-bench --help
./placement-bench simulator --help
./placement-bench algorithms
./placement-bench algorithms catboost-topn-fitcount
```

`algorithms` выводит доступные алгоритмы, типы аргументов, defaults, допустимые
границы и примеры команд. Список формируется из того же реестра, что интерфейс.

Для запуска на датасете нужна папка с `snapshot.pb` и `operations.pb`:

```sh
./placement-bench run \
  --dataset /path/to/dataset \
  --algorithm catboost-topn-fitcount \
  --param topN=20 \
  --param seed=44 \
  --catalogue-limit 32 \
  --max-operations 200
```

Уберите `--max-operations`, чтобы воспроизвести весь поток. Без `--catalogue-limit`
используется полный каталог. Каждый `--param key=value` должен быть объявлен
выбранным алгоритмом; например, CatBoost Top-1 не требует параметров.
JSON результата выводится в stdout, ошибки — в stderr. `--probe-cap` отдельно
задаёт ограничение копий для диагностических проб.

Чтобы включить поиск переездов после отказа Geometric Best Fit, выберите
`--algorithm reactive-ilp`. Алгоритм доступен и в симуляторе.
[Параметры и пример запуска](one-cloud-schedulers-benchmark/docs/algorithms/reactive-ilp.md).

Датасеты не включены в репозиторий. Импорт использует штатную нормализацию
атомарных pod/shard-запросов и начинает с пустого облака, а не восстанавливает
занятость snapshot. GPU по умолчанию моделируются как синтетические устройства
NVIDIA/Intel с 20 долями; это не восстановленная топология физических GPU.

## Профилирование JVM

Распакуйте релиз для своей платформы и задайте `ASYNC_PROFILER_HOME` при запуске.
[Релизы async-profiler](https://github.com/async-profiler/async-profiler/releases)

```sh
export ASYNC_PROFILER_HOME=/path/to/async-profiler
./placement-bench simulator
```

В нижней панели откройте **Профилирование** и выберите CPU, аллокации или wall
clock. В режиме запуска доступны **1 событие**, **10 событий** и **N событий (все)**.
Режим **(все)** доступен и без профилирования: он обрабатывает весь остаток
сценария без промежуточной отрисовки. Для пошагового просмотра без профиля
есть режим **Авто**.
Нажмите **Выполнить**. Симулятор обработает события во всех выбранных алгоритмах.
При включённом профилировании он скачает один файл. HTML показывает стеки обработки событий, JFR содержит
запись JVM. Если для графика мало сэмплов, выберите больше событий или откройте
более длинный сценарий.

Для прогона одного алгоритма на датасете используйте `--profile`.

```sh
./placement-bench run \
  --dataset /path/to/dataset --algorithm fitcount --catalogue-limit 32 \
  --profile fitcount.jfr --profile-event cpu > fitcount.json
```

Профиль начинается после подготовки сценария и охватывает воспроизведение,
включая инициализацию алгоритма и контрольные пробы. Для HTML укажите новый файл
с расширением `.html`. Для записи нужен `bin/asprof`, для HTML также нужен
`bin/jfrconv`. Для сравнения задержек используйте отдельные прогоны с теми же
параметрами без `--profile`.

## Добавить алгоритм

Нужны **реализация и обязательное описание параметров**. Базовый класс —
[`SchedulingAlgorithm`](one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/engine/SchedulingAlgorithm.java).
Общее планирование и проверку ограничений предоставляет `GreedyPlanner`.

Например, создайте
`one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/algorithms/firstfit/FirstFit.java`:

```java
package one.cloud.benchmark.algorithms.firstfit;

import java.util.List;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class FirstFit extends SchedulingAlgorithm {
    private final GreedyPlanner planner;

    public FirstFit(SchedulingContext context) {
        super(context, 0);
        planner = new GreedyPlanner(context);
    }

    public static AlgorithmDefinition definition() {
        return new AlgorithmDefinition(
                "first-fit",
                "First Fit",
                List.of(),
                (context, catalogue, parameters) -> new FirstFit(context));
    }

    @Override
    public void schedule(Request request) {
        for (int host = 1; host <= context.model().hostCount; host++) {
            for (int numa : planner.numaNodes(host)) {
                final var placement = planner.plan(request, host, numa);
                if (placement != null) {
                    context.place(request, placement);
                    return;
                }
            }
        }
    }

    @Override
    public long plannerCalls() {
        return planner.planCalls();
    }
}
```

Откройте короткий файл
[`Algorithms.java`](one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/algorithms/Algorithms.java).
Добавьте импорт своего класса и одну регистрацию в метод `registerAll()`:

```java
register(FirstFit.definition());
```

Это единый список всех алгоритмов проекта, без деления на встроенные и пользовательские.
Параметры и фабрика живут в `definition()` рядом с реализацией.
`List.of()` явно означает отсутствие параметров. Для настраиваемых алгоритмов
схема содержит `Parameter.integer`, `number`, `bool` или `text`; фабрика передаёт
проверенные значения в конструктор. Для каждого параметра обязательны короткое
описание и значение по умолчанию; числовые параметры также задают границы.
Описание видно при наведении в UI и в CLI-справке.
[Пример с параметрами](one-cloud-schedulers-benchmark/docs/simulator.md#algorithms).

На этом подключение закончено: служебный реестр, валидацию, UI и CLI менять или
читать для добавления алгоритма не требуется.

Повторите `./gradlew build installDist`. Алгоритм появится в UI и CLI автоматически:

```sh
./placement-bench algorithms first-fit
./placement-bench run --dataset /path/to/dataset --algorithm first-fit
```

## Где что находится

- [Java-модуль и формат данных](one-cloud-schedulers-benchmark/README.md).
- [Реализации алгоритмов](one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/algorithms).
- [Движок и учёт ресурсов](one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/engine).
- [Симулятор и HTTP API](one-cloud-schedulers-benchmark/src/main/java/one/cloud/benchmark/simulator).
- [Нормализация и воспроизведение датасетов](one-cloud-schedulers-benchmark/docs/runner/README.md).
- [Серия бенчмарков: конфиг, прогрев JVM и метрики](one-cloud-schedulers-benchmark/docs/runner/README.md#experiment-matrix).
