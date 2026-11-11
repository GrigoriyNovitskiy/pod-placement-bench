# Geometric best-fit

Status: implemented in the standalone benchmark as
[`GeometricBestFit`](../../src/main/java/one/cloud/benchmark/algorithms/geometric/GeometricBestFit.java),
selected by `--algorithm geometric-best-fit`. This does not install or replace a
production one-cloud scheduler. The algorithm uses normalized residual L2 squared;
its fixed settings are not measured optima or a guarantee of better utilization.

In one-cloud terminology, a host is a minion's machine; a candidate includes its
NUMA placement and concrete devices for the pod's regular shards. A shard is a
storage volume, and stripe layout is part of its physical allocation.
This adapts the norm-based approach in [vector bin packing](https://www.microsoft.com/en-us/research/wp-content/uploads/2011/01/VBPackingESA11.pdf)
to our heterogeneous, constrained placements.

## Score

For each feasible complete plan `p`, minimize:

```text
A_f(p) = distinct budget cells in family f with actual debit Delta_b(p) > 0
x_b(p) = max(0, R_b - Delta_b(p)) / C_b
G(p)   = sum over nonempty families f of mean over b in A_f(p) of x_b(p)^2
```

`R` is current remaining supply; `C` is initial full usable capacity, including
normalized storage views. Precompute reciprocal capacities once. Scratch
`CapacityLedger.load` loads current `R`, not initial `C`; raw host `alloc` is
also insufficient for storage. Sum, rather than average, the family contributions.

Use actual `CapacityLedger.Debit` entries, aggregated per cell across the whole
atomic request. Do not infer consumption from manifest allocation profiles.
Group positive entries as follows; numbers are protobuf `ResourceType` values:

| Family | Budget cells |
| --- | --- |
| CPU | `1, 12, 15`, host and selected NUMA |
| RAM | `2`, host/NUMA, and MEMDISK device extents `18` |
| Network out / in | `3, 13` / `4, 14`, two families |
| Network queues | `16` |
| NVIDIA / Intel GPUs | `11` / `17`, two families |
| Device space | `18`, one family per primary physical media; MEMDISK goes to RAM |
| Device spindles | `19` or the alternative spindle slot, one family per selected allocation media |
| Device IOPS | `20`, one family per primary physical media |

Exclude derived host storage projection adjustments and spindle capacity lost only
through alternative-view exclusion. Genuine scalar host storage debits `5..10`
still enter the corresponding media space/spindle families. Count shared physical
extents/IOPS once, even when a device has alternative media or several volumes.

Omit unknown/unbounded IOPS cells from both the score and family cell counts,
including the `Double.MAX_VALUE` sentinel. A positive debit against zero capacity
is an error, not an epsilon denominator. A plan with no scored debits has score
zero. Family grouping and averaging are heuristics:
NUMA/stripe cell counts affect the score; original VBP guarantees do not transfer.
Unused GPU/NVMe capacity receives no protection from this score.

## Implemented execution path

1. `Engine.schedule` announces a `Request` containing the pod and any jointly
   created regular shards. Its member resource IDs refer to immutable allocation
   and constraint profiles. The engine supplies current remaining resource budgets
   and placement/group/domain state, rather than instantaneous CPU utilization.
2. `GeometricBestFit extends SchedulingAlgorithm` enumerates hosts in ID order and
   each `GreedyPlanner.numaNodes(host)` in its existing order. Zero denotes an
   unbound candidate; it cannot bypass NUMA-aware demand on a minion with NUMA.
3. `GreedyPlanner.plan(request, host, numa)` checks candidate masks/pins, loads
   host-local scratch supply, and greedily constructs one complete same-host plan.
   It accounts for dedicated CPU conversion, host/NUMA resources, layouts, stripes,
   metadata, whole disks, spindles, IOPS and device/media exclusions. `canPlace`
   checks combined hard limits and dependencies. A failed greedy plan is not a
   proof that every assignment on that minion is infeasible.
4. `GreedyPlanner.normalizedResidualL2Scorer()` delegates to the shared
   `CapacityLedger` helper. It uses `prepare` with reusable scratch/debit, then
   computes the score above from actual positive entries. Initial full capacities
   are captured once; current free capacity is loaded for each candidate. Canonical
   placements, revisions and notifications do not change while scoring.
5. Retain the lowest-score owned plan. Exact ties use seeded reservoir sampling,
   without a floating-point tie epsilon. Call `context.place(request, bestPlans)`
   once if a winner exists; otherwise leave the request unplaced. The engine
   validates and commits the complete unit, then invokes its notifications.

The fixed score uses exponent **2** and every family weight **1**, all host/NUMA
anchors, no cutoff, no migration and no periodic update. Other NUMA components and
device choices within an anchor remain greedy. Stream candidates and retain only
the winner. Removals need no score-cache updates because current supply is reloaded.

GPU feasibility can include individual devices and shares when the device model
is enabled. This L2 score still uses aggregate NVIDIA/Intel budget entries 11/17;
it does not score per-GPU fragmentation. All candidate construction/scoring is
scheduler arithmetic: no application, RPC or real pod/shard lifecycle runs while
trying a candidate. Only the chosen model placement is committed in the benchmark;
production lifecycle integration would follow that decision boundary.

## Select parameters against fitcount

Freeze units, order, catalogue, batch size, cache policy and seeds for all
algorithms. Use saturation and churn scenarios with dependency-valid atomic
create/delete lifetimes; the existing logs are synthetic, empty-start inputs.
Fix delete IDs before replay; deleting a declined unit is a scenario no-op.
Do not use observed target hosts or future trace events as scheduling hints.

Only L2 squared is currently implemented; exponent/weights are not runtime
configuration options. A future norm sweep can compare family mean `x` (L1),
family mean `x^2` and family max `x` (L-infinity), summing family contributions
with fixed weights. Select on three clouds, freeze the choice, then evaluate
seven held-out clouds with at least three seeds.

Report admitted units/resources, p50/p95/p99 of full `schedule` including planning,
validation, commit and updates, memory and planner calls. Report initialization
separately. Diagnose fragmentation with remaining greedy physical fit counts,
using a common cap and declared probe catalogue outside operation timing. Show
per-cloud results and a macro-average. Utilization is the primary selection goal;
report the time/quality trade-off separately. Local geometric score alone does
not establish better admission or packing than fitcount.

Scarcity pricing is a separate challenger: identical global prices and identical
debits cannot distinguish hosts. [CvxCluster](https://arxiv.org/html/2605.01614v1)
combines class prices with batch ordering; our fixed-order experiment needs an
explicit demand model and a packing tie-breaker. A later LP-priced variant can
minimize `(sum_b lambda[class(p), b] * Delta_b(p), G(p))` lexicographically, with
prices and debits in matching units. Include price updates in operation time.
`1/free` is not an LP dual price. The current planner validates
the pending request; forecasting catalogue configurations needs a separate
physical oracle or a documented relaxation.
