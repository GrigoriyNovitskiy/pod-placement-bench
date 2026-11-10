# Fit-count baseline

The flat model changes storage, not the scheduling problem. Allocation profiles,
disk geometry, host/NUMA/device inventory, candidate masks, pins, topology limits
and dependency links remain available. There is no need to reconstruct services,
manifests, filter expressions or the control plane to use them.

Fit-count is the number of additional copies of a request shape that a host can
physically accommodate, up to an explicit common cap. It is not the number of
eligible hosts. A pod and its regular shard form one atomic request; their combined
shape participates in physical fit-count and their full placements commit together.
Colocation follows the retained constraints rather than the request wrapper itself.

## Two separate questions

The physical allocator consumes scalar capacity, dedicated CPU, NUMA capacity,
device extents, spindles and IOPS. To calculate fit-count, repeatedly allocate a
shape into scratch capacity until it fails or reaches the cap. Scalar-only shapes
can use a bounded division fast path. Zero-demand shapes still need the cap.

The placement validator checks an actual complete assignment against candidate
masks, pins, physical budgets, group/domain limits and dependencies. It validates
the combined request, including interactions between its members, before committing
anything. Repeated hypothetical copies for physical fit-count do not increase
affinity or dependency counters: a replica limit of one must not turn physical
fit-count into one. Actual candidate placements must satisfy those limits.

Disk planning is an algorithm choice. A deterministic greedy volume/device planner
is useful for the baseline and repeated-copy calculation; it is not an exhaustive
feasibility oracle. LP and other algorithms may propose their own assignments. The
shared validator checks their geometry directly rather than requiring the greedy
planner to reproduce them.

Host aggregate storage and per-device storage describe coordinated views of the
same allocation. They are not two independent storage demands. Do not add derived
disk footprint to a host ledger that already includes that footprint. Actual
stripe rounding, metadata and whole-disk consumption must be derived from the
chosen geometry. Alternative media are views of one physical device, with shared
extent/IOPS consumption and the appropriate spindle exclusion.

The engine normalizes host storage capacity from the full usable device inventory,
including alternative views. Exported host hardware totals can describe only the
primary media and an old mix of allocations. Read remaining budgets through
`SchedulingContext.available`, rather than treating those raw host totals as an
independent storage bound. ROOTFS is not an allocatable storage class.

## Choosing a candidate

Prepare a declared catalogue of shapes and their frequencies from the common
visible input. All algorithms receive the same information; discovering future
trace requests during replay is not allowed. A shape key includes allocation and
disk requirements, not just its allocation-profile ID. Group identity and pins
belong to actual eligibility, not physical multiplicity.

For each shape, sum its per-host fit-count and normalize by its declared frequency.
Consider shapes in increasing order of this weighted total. For the first shape,
retain candidate placements with the smallest loss of fit-count; repeat for the
next shape among surviving candidates. This lexicographic comparison preserves
the production selection rule more closely than minimizing one sum of losses.
Use seeded tie-breaking over the declared host/NUMA order. Fit-count produced by
a greedy disk planner can be nonmonotonic after an allocation; preserve and test
that behavior rather than silently replacing it with an exact packing optimum.

## Cache

Keep the useful part of `FitCountCache`: multiplicity by shape and host, totals,
lazy refresh of changed hosts, and sample ordering with valid lower bounds.
Represent hosts and shapes by integer IDs. Choose dense or sparse storage from
measured shape/host cardinalities; do not allocate their full cross-product by
default. Bound cached values and refresh work explicitly.

Physical cache entries depend on host capacity revisions. A committed request or
removal updates each affected host revision. Feasibility caches also depend on
global state revision, because a group/domain/dependency change can affect another
host without changing its physical capacity. Candidate scoring uses a scratch
overlay for tentative assignments on the scored host. Real commits and removals
update revisions and invoke hooks. Their cache updates count toward replay time.

## Scope and validation

`CapacityLedger` validates and accounts for physical assignments.
`GreedyPlanner` provides deterministic greedy geometry and repeated-copy
calculation. `Engine` supplies atomic commits, removals and dependency
counters. `FitCount` compares candidate losses using `FitCountCache`.
Preallocation, preemption, preservation, evacuation, quotas, fairness, RPC and
control-loop orchestration are excluded.

The sample catalogue is explicit. Cache rows are bounded by a caller-supplied
limit, while shape totals and ordering metadata survive eviction. Conservative
lower bounds account for every changed host, including multiple hosts in one
atomic commit. The first access to an absent row visits all hosts; a warm row
visits only hosts changed since its generation.

The empty-start policy treats committed resources as ready. Unknown request IOPS
consume no modeled IOPS; known values are split by stripe count and capped per
device. Unknown device IOPS remain unbounded as declared in extraction. Dedicated
CPU is converted using the selected host's power. NUMA-aware demand on a host
with NUMA inventory requires a child placement; selecting NONE cannot bypass
fragmentation. The baseline enumerates NUMA anchors as well as hosts, while
volume/device order remains deterministic greedy planning.

Consolidated tests check combined budgets, atomic failure, removal/counter
restoration, hard versus soft limits, dependency readiness, geometry, alternative
media and cache equality with recomputation after placement/removal. `Scenario`
now prepares joint synthetic lifetimes and `DatasetRunner` executes them through
the CLI. Dedicated JMH fixtures remain separate future work.

The existing traces start empty and are synthetic. Joint scheduling requires a
scenario transformation with explicit grouped create/delete lifetimes; the raw
separate events must not be relabeled as joint decisions without that step. Keep
identical units, batches and ordering for every algorithm.

Exact reconstruction from an occupied snapshot needs two additional decisions:
record which media view each device use consumed, and distinguish placement from
dependency readiness. The current snapshot does not explicitly preserve those
facts. Normalize/infer them with checked rules or extend the schema before claiming
exact snapshot replay. Empty-start replay does not require reconstructing them.

The dataset already approximates unsupported filters, some inherited topology
policy, shared-storage owner templates and GPU sharing/model details. See each
dataset's `summary.json`. These limitations come from extraction/modeling choices,
not primitive arrays. The baseline will measure the declared dataset problem,
not reproduce every production decision bit for bit.
