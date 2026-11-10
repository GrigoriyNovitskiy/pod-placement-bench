# Placement algorithms and datasets

This module contains the public protobuf schema, primitive Java model, streaming
decoder and algorithm API. Its only runtime dependency is `protobuf-java`; it does
not depend on any other one-cloud module. Private control-state extraction stays in
`one-cloud-master`.

The `one.cloud.benchmark` namespace is organized into four packages.

- [model](src/main/java/one/cloud/benchmark/model) contains the placement model, requests and assignments.
- [engine](src/main/java/one/cloud/benchmark/engine) defines the algorithm contract and implements
  placement state, validation, capacity accounting and planning.
- [algorithms](src/main/java/one/cloud/benchmark/algorithms) contains the fit-count implementation.
- [dataset](src/main/java/one/cloud/benchmark/dataset) contains the protobuf decoder and reader CLI.

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

`src/main/proto/placement_dataset.proto` defines the version-2 wire format.
Existing datasets remain compatible: the protobuf package and field numbers are
unchanged. Generated Java types use `one.cloud.benchmark.dataset`.

```java
import java.nio.file.Path;

import one.cloud.benchmark.dataset.PlacementDataset;

final var data = PlacementDataset.load(Path.of("snapshot.pb"), Path.of("operations.pb"));
final int profile = data.constraints[resource];
final boolean allowed = data.candidates.allows(data.rules.candidates[profile], host);
```

Each resource has two primitive columns: allocation and constraint profile IDs.
Shared profiles use flat primitive arrays; candidate sets use bitsets or sorted
IDs. Protobuf records, strings, label maps and filter ASTs are not retained.

An algorithm extends `PlacementAlgorithm` and implements one method:

```java
import one.cloud.benchmark.engine.PlacementAlgorithm;
import one.cloud.benchmark.engine.PlacementContext;
import one.cloud.benchmark.model.PlacementRequest;

public final class MyAlgorithm extends PlacementAlgorithm {
    public MyAlgorithm(PlacementContext context, long seed) {
        super(context, seed);
    }

    @Override
    public void schedule(PlacementRequest request) throws Exception {
        // Choose assignments, then call context.place(request, plans).
    }
}
```

The array overload calls the single-request method in order by default. Batch
algorithms may override it to plan the entire visible window.

`PlacementPlanner` is the shared greedy geometry helper for host-selection
algorithms. Iterate `numaNodes(host)` and call `plan(request, host, numa)` inside
the active scheduling window. A returned plan satisfies the common validator;
failure to find a plan is not proof of infeasibility. LP/ML algorithms may submit
their own assignments directly.

One `PlacementRequest` is an atomic unit, such as a pod with its regular shards.
Its resources are placed together or left unplaced together. Colocation comes from
the model's links; atomicity alone does not require the same host. The outer array
is a declared scheduling window: one request supports online algorithms; several
requests allow joint planning by LP solvers or ML models. Units are disjoint and
have the same dependency-valid order for every algorithm. Commit any subset in
that order, at most once per unit. Leaving a unit unplaced is a decline, not proof
of infeasibility. Unexpected backend errors, cancellation and timeouts fail the run.
Declared bounded optimization may submit its best feasible solution under a recorded
termination policy; reaching that limit does not prove infeasibility.

`PlacementModel.from(data)` shares only static primitive tables, with no reference
to the decoded dataset, observed placements or operation log. Treat all shared
arrays as read-only. `PlacementContext` supplies current capacity, placements and
group/domain counters. `revision()` tracks every state change; a per-host
`capacityRevision(host)` also covers that host's NUMA and device budgets. Algorithms
own indexes and caches; the common engine owns validation, debit, release and
counters.

Read remaining supply through `context.available`. Host storage budgets are
coordinated projections of usable device capacity, including alternative media;
raw host allocation profiles are not an independent storage limit. Device extents
and IOPS are shared across media views. ROOTFS is not allocatable storage.

`Placement` describes the selected host, optional NUMA inventory resource and device
IDs partitioned by disk plan. Its arrays are borrowed: a scoring loop can reuse
buffers and a `Placement[]` rather than allocate candidate objects for every host.
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

`PlacementState` implements the common engine for empty-start replay: resource and
disk budgets, NUMA, dedicated CPU conversion, candidate masks, pinning, hard
topology/count limits and resource/group dependencies. Soft limits do not reject
placements. Successfully committed resources are immediately ready; READY_GROUP
requires all catalogue members, and shared storage uses existing placed owners.
Removing a unit cannot invalidate dependencies of remaining placements. A state
belongs to one algorithm and one owner thread; an uncaught scheduling or callback
failure makes it unusable for further mutation.

```java
import one.cloud.benchmark.algorithms.FitCountScheduler;
import one.cloud.benchmark.engine.PlacementState;
import one.cloud.benchmark.model.PlacementModel;

final var state = new PlacementState(PlacementModel.from(data));
try (final var algorithm = new FitCountScheduler(state, 42, catalogue, 100, 64)) {
    state.schedule(algorithm, request);
    if (state.placement(request.resource(0)) != null) {
        state.remove(request);
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

Snapshot allocations are the observed physical ledger. Operation logs are synthetic
and start empty; algorithms must not use observed target hosts or future events as
placement hints. Maintain current occupancy and dependency/domain counters separately
from the shared immutable demand profiles.

The benchmark preserves the dataset model, including its explicit approximations.
Read every `summary.json`: unsupported filters use observed-host masks, the JSON
capture approximates unavailable inherited spine policy, and shared storage uses
snapshot owners rather than creating new host-specific templates. Missing or cyclic
dependency closures remain in the snapshot but are omitted from runnable traces.
