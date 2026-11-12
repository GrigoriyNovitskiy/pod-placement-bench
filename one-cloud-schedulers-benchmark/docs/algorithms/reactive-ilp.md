# Reactive ILP

`reactive-ilp` first runs the existing `geometric-best-fit` for each atomic
create. On a decline, it tries to admit that request by moving up to two
already placed pods off the host where the new request would land. All
placements use the shared engine's resource accounting.

```sh
./placement-bench run --dataset /path/to/dataset --algorithm reactive-ilp \
  --param seed=42 --param maxMoves=2 --param timeoutMs=200
```

Run the same dataset with `--algorithm geometric-best-fit --param seed=42` to
measure the effect of repair. The registry defaults are seed 44, two moves and
200 ms. Use `./placement-bench algorithms reactive-ilp` to inspect the settings.

The search tries up to six source hosts in order of descending free vCPU,
then descending free RAM, then ascending host ID. Each source contributes at
most 24 movable pods, in resource ID order. Candidates are standalone pods
with no host pin, volume plans, GPU demand or outgoing placement dependencies.
The search keeps live dependencies and any dependency targets of the incoming
request in place. For each movable pod it keeps its current placement and
checks up to eight eligible destination hosts in the same host order. Each
host contributes up to two placements from the existing planner.

The ojAlgo model chooses one binary option per candidate pod and one option
for the incoming request. It constrains the CPU and RAM debits on each host
and NUMA node and minimizes the number of moves within that model. The engine
checks the exact selected plans in sequence, including network, disks, GPUs,
host eligibility and dependencies. For two moves it tries both orders. If
validation fails, the search excludes that combination and can solve again,
with up to three solves per source host. The first validated solution is
committed together with the create. A failed repair leaves existing placements
intact. The search covers a bounded neighborhood, so a decline can still
have a solution outside that neighborhood.

The time budget is shared across repair attempts for one create. A feasible
incumbent can be used if it passes validation. The solver and validation can
finish beyond the budget, so it is a best-effort wall-clock limit. Runs close
to the deadline can produce different choices as machine load changes.
Relocations are simulated within the scheduling call and preserve the original
request identity for subsequent deletes. Migration time and rollout disruption
are outside the replay model.

The report includes `ilp_fallbacks` (greedy declines), `ilp_rescued` (creates
admitted by repair), `ilp_moved_units` (committed pod moves), `ilp_solves`,
`ilp_rejected_plans`, `ilp_infeasible`, `ilp_no_incumbent`,
`ilp_budget_exhausted` (declined repairs that reached their time budget),
`ilp_solve_nanos` (solver calls), and `ilp_repair_nanos` (total time after
greedy declines, including solving). Repair time is included in create
latency. Committed moves also update the replay's host occupancy and
placement history checksum.
