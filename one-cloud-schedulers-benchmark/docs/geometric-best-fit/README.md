# Geometric best-fit

Implement normalized residual L2 squared first. These are recommended starting
parameters, not measured optima; improvement over fitcount remains to be tested.
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
`PlacementCapacity.load` loads current `R`, not initial `C`; raw host `alloc` is
also insufficient for storage. Sum, rather than average, the family contributions.

Use actual `PlacementCapacity.Debit` entries, aggregated per cell across the whole
atomic request. Do not infer consumption from manifest allocation profiles.
Group positive entries as follows; numbers are protobuf `ResourceType` values:

| Family | Budget cells |
| --- | --- |
| CPU | `1, 12, 15`, host and selected NUMA |
| RAM | `2`, host/NUMA, and MEMDISK device extents `18` |
| Network out / in | `3, 13` / `4, 14`, two families |
| Network queues | `16` |
| NVIDIA / Intel GPUs | selected device shares in device mode, host `11` / `17` in aggregate mode |
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
Only debited cells enter the score. Other declared scalar dimensions each form their own family.

## Implementation

1. Add `GeometricBestFitScheduler extends PlacementAlgorithm` in
   `one.cloud.schedulers`; implement the single-request overload.
2. Enumerate hosts in ID order and each `PlacementPlanner.numaNodes(host)` in its
   existing order. Use `plan(request, host, numa)` for complete atomic placements.
   Keep the same planner and hard-constraint validation as fitcount.
3. Add one package-private scoring helper inside `PlacementCapacity`. Reuse
   host-local scratch and `prepare` with a reusable `Debit`; read actual entries
   separately from projection adjustments. Reuse allocator geometry for dedicated
   CPU, layouts, stripes, metadata, whole disks and IOPS. Never mutate canonical
   state, revisions or callbacks while scoring, or copy the cloud per candidate.
4. Retain the lowest-score owned plan. Break exact ties by seeded reservoir
   sampling; use the run seed, with no floating-point tie epsilon.
5. Call `context.place(request, bestPlans)` once, or leave the unit unplaced.
   Read fresh supply for each request; removals need no scorer cache updates.

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
