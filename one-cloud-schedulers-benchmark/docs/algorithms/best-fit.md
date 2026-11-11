# Best Fit

Select this algorithm with `--algorithm best-fit`.

Create `BestFit` with a fresh, empty engine using `new BestFit(engine, seed)`.
It scores each feasible single-host plan from `GreedyPlanner` by summing
`remaining / initialCapacity` over host dimensions with positive finite initial
capacity. This uses the host's projected storage budgets. NUMA and device geometry
constrain the plan through the shared validator. The seed resolves equal scores
across the host and NUMA candidates. Best Fit commits the lowest-scoring plan
and leaves the request unplaced when the planner finds none.
