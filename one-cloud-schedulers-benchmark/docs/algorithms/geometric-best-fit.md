# Geometric best-fit

Geometric Best Fit uses squared normalized residuals. The current settings are
starting parameters for evaluation.
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

## Implementation

1. [GeometricBestFit](../../src/main/java/one/cloud/benchmark/algorithms/geometric/GeometricBestFit.java)
   implements the single-request overload of `SchedulingAlgorithm`.
2. The algorithm iterates hosts by ID and visits each anchor from
   `GreedyPlanner.numaNodes(host)`. The shared `plan(request, host, numa)` builds
   and validates complete atomic placements.
3. `CapacityLedger` scores actual debits in a host-local scratch ledger. It
   reuses a `Debit` buffer and uses the allocator's geometry for dedicated CPU,
   layouts, stripes, metadata, whole disks and IOPS.
4. The algorithm retains the plan with the lowest score. It breaks exact ties
   by seeded reservoir sampling.
5. The algorithm reads fresh supply for each request. The winning
   `context.place` call updates the engine and invokes callbacks. The request
   stays unplaced when the planner finds no candidate.

Defaults: exponent **2**, every family weight **1**, every host/NUMA anchor,
no candidate cutoff, no migration and no periodic model update. The existing
array overload preserves request order. Planner failure is not proof of
infeasibility. Stream candidates and retain only the winner.

## Select parameters against fitcount

Freeze units, order, catalogue, batch size, cache policy and seeds for all
algorithms. Use saturation and churn scenarios with dependency-valid atomic
create/delete lifetimes; the existing logs are synthetic, empty-start inputs.
Fix delete IDs before replay; deleting a declined unit is a scenario no-op.
Do not use observed target hosts or future trace events as scheduling hints.

First sweep only three variants: family mean `x` (L1), family mean `x^2` (the
default), and family max `x` (L-infinity); always sum family contributions and
keep weights fixed. Select on three clouds, freeze the choice, then evaluate
seven held-out clouds with at least three seeds.

Report admitted units/resources, p50/p95/p99 of full `schedule` including planning,
validation, commit and updates, memory and planner calls. Report initialization
separately. Diagnose fragmentation with remaining greedy physical fit counts,
using a common cap and declared probe catalogue outside operation timing. Show per-cloud results
and a macro-average. A winner must improve quality and/or time without worsening
the other; otherwise retain the Pareto trade-off. No such comparison has run yet.

Scarcity pricing is a separate challenger: identical global prices and identical
debits cannot distinguish hosts. [CvxCluster](https://arxiv.org/html/2605.01614v1)
combines class prices with batch ordering; our fixed-order experiment needs an
explicit demand model and a packing tie-breaker. A later LP-priced variant can
minimize `(sum_b lambda[class(p), b] * Delta_b(p), G(p))` lexicographically, with
prices and debits in matching units. Include price updates in operation time.
`1/free` is not an LP dual price. The current planner validates
the pending request; forecasting catalogue configurations needs a separate
physical oracle or a documented relaxation.
