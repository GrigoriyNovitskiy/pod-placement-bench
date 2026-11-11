# Local placement simulator

## Run

From the repository root, build once with JDK 25, then launch the installed app:

```sh
./gradlew build installDist
./placement-bench simulator
```

Open `http://127.0.0.1:8765`. The server opens a browser when supported. To select
another port or suppress opening:

```sh
./placement-bench simulator --port 8766 --no-open
```

The launcher uses `JAVA_HOME` or Java from `PATH`; it needs a Java 25 runtime even
if Gradle found a separate build toolchain. Run `./gradlew build installDist` again
after source changes. `./placement-bench simulator --help` shows server options.

Stop the server with Ctrl+C. State is in memory and shared between tabs using the
same server; save a manual scenario before stopping if you want to retain it.

The initial example contains eight hosts and 24 atomic requests, including
pod+volume and fractional GPU requests. Select multiple algorithms with
**Добавить алгоритм**, then use **Следующий запрос**, **+10 событий** or **Авто**.
Pausing stops subsequent steps; an in-flight Java operation finishes first.

Host headers can be dragged; empty canvas space pans the map. These actions,
zooming and pagination never migrate resources. Clicking a pod/shard reveals its
demands, placement, constraints, linked resources and atomic co-members. Manual
deletion schedules the entire atomic unit. Existing future deletions are reported
instead of silently rewriting the queue.

## Scenarios

**Открыть сценарий** accepts a manual JSON file or a pair of native
`snapshot.pb` / `operations.pb` files (128 MiB combined maximum). Native import uses
the same `joint-phases-v1` normalization as the runner, starts with an empty cloud
and does not restore snapshot occupancy. It reports raw and normalized sizes;
manual edits are disabled in this mode.

Manual JSON uses the exact protobuf resource enum names and units (RAM is MiB,
storage is GiB, network is Mbit/s). Forms display RAM in GiB and convert it. A small
scenario can be written directly or saved from the UI:

```json
{
  "hosts": [{"name": "node-1", "resources": {"VCORES": 8, "MEM_MIB": 16384}}],
  "requests": [{
    "name": "api",
    "members": [{"name": "api-pod", "kind": "POD", "resources": {"VCORES": 2, "MEM_MIB": 1024}}]
  }],
  "events": [{"kind": "create", "request": "api"}],
  "catalogue": ["api"]
}
```

`events: null` creates each request once; an empty list creates none. Each request
has 1–16 same-host atomic members (`POD` or `SHARD`). Members optionally specify
`pinnedHost`, `allowedHosts`, and disk plans. Physical host disks specify their
media and `DEVICE_EXTENTS`, `DEVICE_SPINDLES`, `DEVICE_IOPS` capacities; manual
extents are 64 MiB. A scalar SSD/HDD/NVMe demand and a physical disk plan are
different engine inputs: only the latter produces concrete device assignments.

The catalogue is a list of request names. If omitted, up to 32 evenly spaced
requests are sampled. The UI preserves that list when appending requests so prior
choices remain reproducible. For a new empty cloud the first step fills an empty
catalogue from the queued requests. **Параметры сценария → Каталог из очереди**
explicitly replaces it and resets playback. Saved JSON includes the frozen list;
it describes the scenario, not algorithm parameters or a resumable engine dump.

## Algorithms

All registered algorithms, including CatBoost and RandomN, are available through the
Java `AlgorithmRegistry`. Each algorithm declares its own parameters, types,
defaults and bounds. The browser builds the form from this descriptor. Adding or
changing a lane replays the common prefix on a fresh engine.
The comparison is limited to six lanes to keep the local UI manageable.

CatBoost Top-1 and Rollout Top-1 have no parameters: their selected candidate is
deterministic, so the internal FitCount constructor's seed/copy cap/cache values
do not affect the decision. Top-N and RandomN variants expose `topN`, with seed,
copy cap and cache rows under **Дополнительно**. The other built-ins expose seed
under the same section. UI defaults remain seed 44, N 20, cap 10 and cache 64 for
algorithms that use them.

Bulk FitCount and DP keep their upstream defaults (seed 42, cap 128, cache 256).
The simulator announces one atomic request per step; the CLI can additionally
announce service bursts with `--bulk-size`. The online `rollout` algorithm requires
a checksum-verified prior manifest and is disabled in the UI until prior upload is supported.
It remains available in the CLI with `--rollout-prior FILE`. This is separate from
the CatBoost rollout models, which remain available in the UI as before.

All algorithms are connected in one small list:
`src/main/java/one/cloud/benchmark/algorithms/Algorithms.java`. Add an import and
one entry alongside the existing algorithms:

```java
register(MyAlgorithm.definition());
```

Each implementation declares its own `public static AlgorithmDefinition definition()`:
ID, display name, parameter schema and construction factory. It returns data and
a factory without loading models or reading files. There is no built-in/custom
split, and the internal registry does not import concrete algorithm implementations.

`MyAlgorithm` extends `SchedulingAlgorithm` and commits through its context. An
algorithm without parameters supplies `List.of()`. UI and CLI use the same
definition. Only the implementation class and this small connection list need
to change; the internal registry is not an extension point to edit.

For example, an implementation accepting a numeric weight and a boolean option
declares this method inside its own class, with imports for `AlgorithmDefinition`,
`AlgorithmRegistry.Parameter` and `java.util.List`:

```java
public static AlgorithmDefinition definition() {
    return new AlgorithmDefinition(
            "my-policy",
            "My policy",
            List.of(
                    Parameter.number("weight", "Weight", "Вес критерия при сравнении размещений.", 0.5, 0, 1),
                    Parameter.bool("preferGpu", "Prefer GPU hosts", "Предпочитать GPU-хосты при равных оценках.", false)),
            (context, catalogue, parameters) -> new MyAlgorithm(
                    context,
                    parameters.get("weight").getAsDouble(),
                    parameters.get("preferGpu").getAsBoolean()));
}
```

Common immutable parameter schemas in `AlgorithmParameters` are reused by the
existing implementations; a new algorithm can describe its own parameters directly.
Each parameter requires a short description, shown on hover over its label or
input in the UI and beneath the parameter in CLI help. Empty descriptions are
rejected at registration. Parameter types are integer, number, boolean and string. `.asAdvanced()` moves a
field into the advanced section. The registry fills missing defaults and rejects
unknown keys, incorrect types and out-of-range values before replacing a running
lane. Integer parameters use the exact JavaScript-safe range (up to
9,007,199,254,740,991 in magnitude), avoiding silent seed rounding in the browser.
Effective lane settings are represented as `{"id":"...","parameters":{...}}`.
The same parameters are accepted by the CLI through repeated `--param key=value`.
Use `algorithms` or `algorithms ID` to print the registered schemas and command
examples without opening a dataset.

## Metrics

The UI separates cumulative admissions from active occupancy, pods from shards,
and GPU-pod subsets from all pods. A unit may contain multiple resources, so a unit
latency is not a per-pod latency. Rejected creates and deletion no-ops are counted.
When FitCount is selected, pod admission differences use that lane as baseline.

All 20 dimensions are available, including nonprod/hardware dimensions and physical
disk extents, spindles and IOPS. Aggregate host projections and device dimensions
are separate rows, not additive quantities. A sentinel/unbounded IOPS capacity is
reported as **Без лимита в модели**, with nullable totals/usage, rather than a
fabricated finite utilization. GPU inspection shows the engine's synthetic
NVIDIA/Intel devices and their live free shares, not physical SKU/MIG information.

Create/remove mean, p50, p95, p99 and planner calls are interactive diagnostics.
The viewport, upload, pause and draw time are excluded. Replays rebuild the lane's
diagnostic history; those timings should not be interpreted as controlled speedups.
The separate probe button evaluates up to 16 catalogue requests against all hosts,
with the same cap of 10 copies per host for every lane. This diagnostic cap is
independent of each algorithm's parameters.

## Design and implementation plan

**Goal:** a local, interactive visual wrapper around the existing Java scheduling engine.

**Architecture:** JDK HTTP server on loopback, a single owner thread for engine operations,
independent engine/algorithm instances per comparison lane, and static browser assets.
The browser sends commands and renders engine snapshots; it never decides placements.
The existing CLI, dataset normalization and benchmark replay remain unchanged.

**Stack:** JDK 25, JDK HttpServer, Gson, browser HTML/CSS/JavaScript. No Node build required.

The selected approach keeps simulation next to the engine. An independent JavaScript
simulation would duplicate resource semantics; a desktop toolkit would add packaging
and charting dependencies without improving this local browser workflow.

### Contracts

- Imported `snapshot.pb` and `operations.pb` use `Dataset.load` and `Scenario.from`.
  Display the raw/normalized event counts and source identity. Imported scenarios
  accept playback controls but no arbitrary workload edits.
- Manual JSON describes hosts, atomic request units, member demands, optional disk
  plans/host filters and create/delete events. It compiles to a validated protobuf
  model and an open-ended event list. Unit members colocate and delete atomically.
- Manual topology edits restart playback. Appended requests preserve resource IDs
  and the explicit frozen catalogue; replay rebuilds the state at the same cursor.
- Adding an algorithm creates a fresh engine and replays the same prefix with its
  seed and the common catalogue. No copying or migration of placements.
- Show cumulative admissions separately from active occupancy, pod/shard/GPU
  counts, creation/removal latency, planner calls and all resource dimensions.
  Initial capacity comes from the engine, including disk projections. Device
  dimensions are measured on disks, not added to host capacities twice.
- GPU inspection exposes synthetic NVIDIA/Intel devices with 20 shares, without
  claiming SKU or MIG topology. GPU placement is performed by the Java planner.
- Fit-count probes run only on request, outside scheduling timing. Timings are
  interactive diagnostics, not controlled benchmark measurements.
- Paginate hosts/events and load detailed host/pod inspection on demand.
- Pan, zoom and host dragging change presentation only. Selected-object links and
  inspectors expose actual model constraints and assignments.
- Local server accepts bounded uploads, serves an explicit static allowlist and
  checks mutation headers/origin. It never reads user-supplied filesystem paths.

### Tasks and verification

- [x] Scenario inputs: `ScenarioInput`, `ManualScenario`, `AlgorithmRegistry`.
  Verify immutable append IDs/catalogue, invalid resources/GPU demands, atomic
  members, disk/filter translation and Java factory registration.
- [x] Session: `SimulatorSession`, using existing Engine schedule/remove calls.
  Verify create/delete/refusal, independent lanes, reset/replay, append stability,
  native dataset prefix parity, resource accounting and GPU shares.
- [x] HTTP: `SimulatorServer`, loopback owner executor and bounded JSON/binary APIs.
  Verify lifecycle, bad input preservation, mutation protection and native import.
- [x] Browser: classpath `index.html`, `app.js`, `views.js`, `style.css`; Gradle `runSimulator`.
  Exercise manual host/pod edits, atomic deletes, algorithm comparison, upload,
  resource metrics, probes, selection, pan/zoom and save/load against the server.
- [x] Independent review, fixes, `git diff --check` and module build. Leave changes
  uncommitted as requested. Keep local datasets, logs and browser test artifacts out
  of version control.

### Review focus

Engine thread affinity; exact Request identity on deletion; catalogue changes during
append; host/device resource double counting; stale browser responses after reset or
algorithm changes. Validate each with targeted session/API/browser checks.
