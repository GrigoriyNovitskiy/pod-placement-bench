# Placement algorithms and datasets

This module contains the public protobuf schema, primitive Java model, streaming
decoder and algorithm API. Its only runtime dependency is `protobuf-java`; it does
not depend on any other one-cloud module. Private control-state extraction stays in
`one-cloud-master`.

The `one.cloud.benchmark` namespace separates the model, engine and run tooling.

- [model](src/main/java/one/cloud/benchmark/model) contains the static cluster specification,
  requests and assignments.
- [engine](src/main/java/one/cloud/benchmark/engine) runs scheduling windows and owns
  validation, resource accounting and current placements.
- [algorithms](src/main/java/one/cloud/benchmark/algorithms) gives each algorithm its own package,
  starting with [FitCount](src/main/java/one/cloud/benchmark/algorithms/fitcount).
- [dataset](src/main/java/one/cloud/benchmark/dataset) contains the protobuf decoder and reader CLI.
- [runner](src/main/java/one/cloud/benchmark/runner) compiles and replays shared scenarios.
- [cli](src/main/java/one/cloud/benchmark/cli) selects a dataset and algorithm and emits JSON results.

Build from the `pod-placement-bench` repository root:

```sh
./gradlew :one-cloud-schedulers-benchmark:build
./gradlew :one-cloud-schedulers-benchmark:runPlacementDatasetRead \
  --args='/path/to/snapshot.pb /path/to/operations.pb'
```

The repository builds with JDK 25 toolchain and public dependencies only. Its root
wrapper downloads Gradle 9.3 from the public distribution service and verifies its
SHA-256. The subproject and generated artifact are named
`one-cloud-schedulers-benchmark`.

## Run a dataset

A dataset directory contains `snapshot.pb` and `operations.pb`. Run the CLI from
the repository root:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm fitcount --seed 42'
```

For an explicit short smoke run:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm fitcount --max-operations 200 --catalogue-limit 32'
```

Built-in algorithms are `fitcount`, `mean-fitcount`, `geometric-best-fit` and `fgd`.
`--fit-cap` defaults to 10 and `--cache-rows` to 256. The default catalogue contains
all initial atomic units; a positive `--catalogue-limit` selects a deterministic,
reported sample. `--max-operations` truncates normalized atomic events, without
changing the catalogue or the static cluster. `--help` lists the options.

`devices20` is the default GPU model. Use `--gpu-model aggregate` to reproduce
legacy pooled host accounting. A normal run uses synthetic individual devices:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm fitcount'
```

In `devices20`, each host's integer NVIDIA/Intel GPU capacity becomes that many
separate devices of the corresponding type, with 20 shares per device. A positive
request below one GPU must be a multiple of 0.05 and fit on one device. An integer
request of one or more GPUs requires that many completely free devices. The
model rejects fractional host capacities, requests such as 1.5 GPUs, and demands
outside the 0.05 grid. Grid values represented within four double-precision ULPs
of the canonical amount, or as the exact float32 representation of a grid value,
are accepted. Device-mode GPU accounting uses the corresponding integer share
count and canonical GPU amount; arbitrary nearby demands are not rounded to the
grid. Unsupported data fails scenario preparation rather than turning into a
placement decline.

This is a synthetic geometry over the existing aggregates. It does not recover
physical GPU models, host sharing modes, GPU memory requirements or transcoding
constraints. Empty-start replay creates device occupancy through algorithm choices;
observed snapshot GPU placements are not inferred. The same model governs common
validation, greedy plans, physical fit-count and quality probes for every algorithm.
For example, free shares equivalent to `[0.2, 0.2, 0.2]` cannot satisfy a 0.6 request.
The JSON report records `gpu_model`, device-mode scenario digests differ from
aggregate-mode digests, and the history checksum includes selected GPU IDs.

For a standalone command, build the standard application distribution. Its script
uses `JAVA_HOME` or `PATH`, so select a Java 25 runtime before launching it; Gradle
`run` selects the configured toolchain automatically.

```sh
./gradlew :one-cloud-schedulers-benchmark:installDist
one-cloud-schedulers-benchmark/build/install/one-cloud-schedulers-benchmark/bin/one-cloud-schedulers-benchmark \
  --dataset /path/to/sc --algorithm fitcount
```

Mean FitCount (`--algorithm mean-fitcount`) shares the original FitCount planner
and cache. It minimizes the mean loss of copies across catalogue entries, with
each shape weighted by its frequency. Both variants honor `--fit-cap` and
`--cache-rows`. See [FITCOUNT.md](FITCOUNT.md#mean-fitcount) for the scoring rule
and its cost.

Geometric best-fit uses normalized residual L2 squared with fixed family weights.
It shares planner geometry and hard constraints with fitcount and streams candidates
while retaining only the winner. The copy cap still controls common quality probes;
the cache-row option only affects the two FitCount variants.

FGD is available with `--algorithm fgd` and requires the `devices20` GPU model.
It minimizes the frequency-weighted change in unusable GPU shares for the declared
CPU/GPU workload, including the choice of a physical card for fractional requests.
CPU-only clusters and empty catalogues use seeded tie-breaking. Its catalogue uses
the same declared input as fitcount; the copy cap controls common quality probes,
and the cache-row option has no effect on FGD. See [FGD.md](FGD.md) for its metric,
source references and the bounded search used for compound atomic requests.

The CLI writes one JSON result on stdout; errors go to stderr with a nonzero exit.
It reports source/scenario identity, limits, admissions and declines, schedule and
removal percentiles, sampled heap use, planner calls and a history checksum.
Heap samples are taken before initialization, at occupied checkpoints and at replay
end; this is not a per-operation heap peak.
Initialization and occupied-checkpoint physical-fit diagnostics are separate from
operation timing. Quality probes are outside the clock but can still influence
later JIT/GC behavior; this is a diagnostic replay, not an isolated JMH latency test.

`Scenario` cold-compiles the synthetic log into joint pod/shard lifetimes. Churn
expands to complete atomic units and their dependency closure. The same units,
order, catalogue and delete IDs are used for every algorithm; a declined unit's
delete is a no-op. Snapshot occupancy is not imported. See the
[runner design](docs/runner/README.md) for normalization and measurement contracts.

`src/main/proto/placement_dataset.proto` defines the version-2 wire format.
Existing datasets remain compatible: the protobuf package and field numbers are
unchanged. Generated Java types use `one.cloud.benchmark.dataset`.

- Emit indexed records in ascending ID order within each kind. Host IDs run from
  `1` through `host_count`. Zero means absent except for candidate sets, where
  `0` permits all hosts and `1` permits none. Higher sets list sorted, unique
  host IDs. `candidate_count` is the largest set ID, including reserved set `1`.
- The NUMA and DISK `ordinal` is the local NUMA index plus one. Zero means
  unspecified. Domain levels `1` through `5` mean host, NUMA, rack, hall and
  spine. Higher levels identify opaque attributes. A missing domain value is
  zero and fails a constraint at that level. At level `2`, the host resource
  ID represents an unbound placement and a selected NUMA child supplies its own ID.

```java
import java.nio.file.Path;

import one.cloud.benchmark.dataset.Dataset;

final var data = Dataset.load(Path.of("snapshot.pb"), Path.of("operations.pb"));
final int profile = data.constraints[resource];
final boolean allowed = data.candidates.allows(data.rules.candidates[profile], host);
```

Each resource has two primitive columns: allocation and constraint profile IDs.
Shared profiles use flat primitive arrays; candidate sets use bitsets or sorted
IDs. Protobuf records, strings, label maps and filter ASTs are not retained.

An algorithm extends `SchedulingAlgorithm` and implements one method:

```java
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class MyAlgorithm extends SchedulingAlgorithm {
    public MyAlgorithm(SchedulingContext context, long seed) {
        super(context, seed);
    }

    @Override
    public void schedule(Request request) throws Exception {
        // Choose assignments, then call context.place(request, plans).
    }
}
```

The array overload calls the single-request method in order by default. Batch
algorithms may override it to plan the entire visible window. Treat the array as
read-only.

`GreedyPlanner` is the shared greedy geometry helper for host-selection
algorithms. Iterate the borrowed, read-only `numaNodes(host)` array, where zero
denotes an unbound placement. Call `plan(request, host, numa)` inside the active
scheduling window. A returned plan belongs to the caller and passes the common
validator. A null result means the greedy search found no plan. LP/ML algorithms
may submit their own assignments directly.

One `Request` is an atomic unit, such as a pod with its regular shards.
Its resources are placed together or left unplaced together. Colocation comes from
the model's links; atomicity alone does not require the same host. The outer array
is a declared scheduling window: one request supports online algorithms; several
requests allow joint planning by LP solvers or ML models. Units are disjoint and
have the same dependency-valid order for every algorithm. Commit any subset in
that order, at most once per unit. Leaving a unit unplaced is a decline, not proof
of infeasibility. Unexpected backend errors, cancellation and timeouts fail the run.
Declared bounded optimization may submit its best feasible solution under a recorded
termination policy; reaching that limit does not prove infeasibility.

`ClusterSpec.from(data)` shares static inventory, workload profiles and constraints
across runs. It keeps no reference to the decoded dataset, observed placements or
operation log. `SchedulingContext` supplies current capacity, placements and
group/domain counters. `revision()` tracks commits and removals.
`capacityRevision(host)` advances when a host's physical budget changes, including
its NUMA and device budgets. Algorithms own their indexes and caches.

Supply and demand use the same resource units. Read remaining supply through
`context.available`. Host storage budgets project usable device capacity,
including alternative media. Use these projections in place of raw host storage
profiles. Device extents and IOPS are shared across media views. ROOTFS is not
allocatable storage.

An `Assignment` specifies a resource's host, NUMA node and device IDs. Its
`numa` is zero for an unbound placement. Keep the borrowed arrays stable
during a context call. A scoring loop can reuse them after the call returns.
Its `devices` array describes disks; its separate `gpus` array contains synthetic
GPU IDs. The five-argument constructor supplies an empty GPU array for existing
aggregate-mode callers. Device-mode requests with positive GPU demand require
explicit GPU IDs; the engine does not choose missing devices on behalf of an
algorithm. GPU shares follow from the resource's demand and each selected device's
type. GPU buffers have the same borrowing, snapshot and read-only contracts as
disk buffers.
The context must validate one assignment per member against the combined tentative
state, including aggregate budgets and intra-unit constraints. `canPlace` is pure:
malformed inputs throw, well-formed infeasible plans return false. It must retain
no buffers or invoke notifications. `place` validates the whole unit, snapshots all
winning buffers before mutation, commits all members, updates revisions, then
calls `placed` in request member order. Submission buffers can be reused on return.
Current placements and notification arguments are read-only engine-owned snapshots.
`removed` sees the state after release. A notification failure aborts the run;
arbitrary callback effects are not rolled back. State changes and notifications
stay on one owner thread; notifications may read state but cannot reenter mutation.
Solver, native and GPU work must finish before its result
is submitted and before `schedule` returns; `close` releases backend resources.

Each `Engine` starts with empty hosts and owns one run's placements, resource
budgets and dependency counters. It executes scheduling windows, validates and
commits assignments, and releases resources on removal. It enforces NUMA,
dedicated CPU conversion, candidate masks, pinning, topology limits and
dependencies. Only hard limits affect validation. Committed resources become
ready immediately. `READY_GROUP`
requires all catalogue members. `SHARED_GROUP` requires a ready snapshot member
of the target storage group on the candidate host. Removing a unit preserves
dependencies of remaining placements. An engine belongs to one algorithm and
one owner thread. An uncaught scheduling or callback failure aborts the run.

```java
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;

final var engine = new Engine(ClusterSpec.from(data));
try (final var algorithm = new FitCount(engine, 42, catalogue, 100, 64)) {
    engine.schedule(algorithm, request);
    if (engine.placement(request.resource(0)) != null) {
        engine.remove(request);
    }
}
```

`catalogue` is a declared array of representative atomic requests. Repeated
physical shapes contribute frequency weights. It is common input prepared before
replay, rather than discovered by looking ahead in the operation log. The final
arguments cap copies per host and resident cache rows. Each resident row uses
one int per host; metadata for every distinct catalogue shape is retained.
Warm rows refresh changed hosts only. Constructor/index work is preprocessing;
lazy cache filling during scheduling is part of operation time.
Record both limits and catalogue order/frequencies with the seed: the copy cap
affects scores, while the row limit should affect runtime only.

Production fitcount counts how many copies of a request shape fit within a host's
physical budget. Its host choice uses the loss of future capacity across known
shapes. This differs from an eligible-host count, which counts each host once when
at least one valid NUMA/device assignment exists. The API supports either metric;
it does not impose a shared scoring function or physical assignment planner.
The production port and cache boundaries are described in [FITCOUNT.md](FITCOUNT.md).

`ClusterSpec.from(data)` and `Scenario.from(data)` use device accounting by default.
Custom algorithms must submit explicit GPU IDs for GPU-bearing requests, or use
`ClusterSpec.from(data, ClusterSpec.GpuMode.AGGREGATE)` for legacy pooled accounting.
The corresponding explicit mode is also available in `Scenario.from`.
`model.gpus` supplies
the immutable topology: `first(host, dimension)` and `end(host, dimension)` bound
the IDs of one host/type, `host(device)` and `type(device)` describe a card, and
`demand(resource, dimension)` returns requested shares. NVIDIA and Intel dimensions
are 11 and 17. GPU IDs start at one in their own namespace; they are not dataset
resource IDs. `model.gpus` is null in aggregate mode.

`GreedyPlanner` selects the tightest fitting GPU for a fractional request,
breaking ties by device ID, and uses completely free devices for whole-GPU
requests. Algorithms that choose their own GPU geometry read current integer
free shares through `context.gpuAvailable(device)` and submit their chosen IDs
in `Assignment.gpus`. The default context method throws for implementations
that do not provide device accounting.

Decode data and construct declared units outside operation timing. Report
algorithm-specific initialization separately. Use a fresh state, algorithm, RNG
and caches for each independent replay or benchmark fork, and declare cold/warm
cache policies. A synchronous operation measurement includes planning, common
validation, atomic commit and index/cache notifications consistently for every
algorithm. A pure-scoring measurement is a separate experiment. Normalize by
attempted units and resources, and report placed/unplaced counts and placement
quality alongside time. Record dataset/scenario digests, seed, backend versions,
thread counts, tolerances, budgets and ML model digests; a seed alone does not
guarantee bitwise reproducibility for LP or ML backends.

The snapshot stores every observed physical copy, including duplicates during
migration. Snapshot `Placement.alloc` measures occupancy independently of the
resource's requested allocation profile. Operation logs are synthetic and start
empty. Algorithms must not use observed target hosts or future events as
placement hints. Maintain current occupancy and dependency/domain counters
separately from the shared immutable demand profiles.

The benchmark preserves the dataset model, including its explicit approximations.
Read every `summary.json`: unsupported filters use observed-host masks, the JSON
capture approximates unavailable inherited spine policy, and shared storage uses
snapshot owners rather than creating new host-specific templates. Missing or cyclic
dependency closures remain in the snapshot but are omitted from runnable traces.
