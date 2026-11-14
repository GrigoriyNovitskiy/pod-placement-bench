# Bulk fitcount

Status: implemented. Use the [shared contract](README.md).

## Decision and objective

`bulk-fitcount` runs ordinary FitCount in the announced request order. For the
same catalogue, seed, copy cap, row limit and `maxTotalFit`, it selects the same
complete plans as sequential FitCount, including every host/NUMA candidate and
seeded ties. Catalogue scarcity order and the adaptive total-fit limit are
refreshed after every placement.

The batch shares physical loss components and greedy physical planning outcomes
for consecutive requests with the same physical shape. Shape identity includes
ordered members, their allocations, disk geometry, GPU demand and internal NUMA
components. Eligibility is evaluated
again for every request, so topology limits, pins, masks and dependencies always
use the current state. Compound pod/shard requests remain atomic.

## Reuse and bounds

A loss component is indexed by catalogue shape and a stable host/NUMA anchor
slot. It is reused while the request shape and that host's physical capacity are
unchanged. Physical geometry and confirmed physical failures are retained by
the same stable anchor slot. `GreedyPlanner.physicalPlan` computes geometry
without checking eligibility. A positive template keeps its original member IDs
and read-only device arrays; a reused plan binds the current ordered member IDs
and passes `canPlace` again. Mask, pin and eligibility failures never become
physical failures. A positive template remains available after a temporary
eligibility failure. `capacityChanged(host)` invalidates all of the host's loss
and physical planning slots after a commit or removal. Global eligibility changes
do not invalidate physical entries; actual positive plans always pass current
eligibility validation.

Loss rows use LRU eviction and retain at most `cacheRows` rows. A separate 64 MiB
batch workspace ceiling can reduce that number or disable sharing. The complete
loss-row budget is reserved before optional geometry is retained. Geometry uses
only the remaining space and cannot reduce the loss-cache capacity. Eviction and
the ceiling affect runtime only. Negative losses from greedy geometry are kept.
No hosts × replicas × catalogue matrix is retained.

All loss rows, retained geometry, anchor slots and request shape metadata are
released when `schedule(batch)` returns or throws. No score sharing crosses invocation
boundaries. The persistent ordinary FitCount cache still tracks physical
multiplicity by catalogue shape and host, as it does for sequential FitCount.
Singleton calls delegate directly to the same FitCount instance and RNG.

## Configuration and diagnostics

The existing defaults remain `fitCap=128`, `cacheRows=256`, `seed=42`.
`maxTotalFit=0` disables the adaptive total-fit limit. Full catalogue frequencies
are supported. Use identical explicit parameters for a sequential comparison;
ordinary FitCount has different defaults.

The runtime profile is `bulk-fitcount-v3`. `marginal_evaluations` counts computed
loss components and `marginal_cache_hits` counts reused components.
`geometry_cache_hits` counts reused physical planning outcomes, including
physical failures and positive plans rejected by fresh eligibility validation.
`planner_calls` counts actual greedy planning calls.
`fitcount_after_probes` counts actual physical kernel probes, excluding cache
hits. `optimizer_workspace_peak_bytes` reports the retained batch workspace,
excluding the shared planner and persistent physical cache. `optimized_bulks`
counts completed batch calls; structural certification and fallback-reason
counters apply to DP.

[DP](dynamic-programming.md) retains its separate frozen-order count/tree
objective, structural gate and detached-trial proposal. It can choose placements
that differ from sequential FitCount; `bulk-fitcount` preserves sequential
selection and amortizes its scoring work.
