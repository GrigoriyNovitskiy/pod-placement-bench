# CP-SAT, bounded search and an SMT reference

Status: planned. Use the [shared contract](README.md). For B=1 the primary gain is
finding alternative feasible NUMA/device geometry. An admission solver alone does
not predict future requests. Bulk and other announced windows are separate series.

## Search and model

First implement bounded Java backtracking over the shared physical kernel:
most-constrained member/volume first, stable ID/device order, reversible debits.
Retain the ordinary FitCount result and include its assignments in every later
candidate pool. Return complete plans, including internal colocation and device
mode choices; do not duplicate disk-layout arithmetic.

For a finite pool P_u of complete plans for unit u:

```text
x[u,p] in {0,1}; a[u] = sum_p x[u,p] <= 1
first maximize sum_u a[u]
then minimize frozen-catalogue physical fitcount loss lexicographically
```

For B=1, score alternatives with the same FitCount loss as the baseline. The
loss objective is a declared future-capacity surrogate, not mixed-demand value.
For a window, the loss is computed on the combined residual state, not by summing
independently scored conflicting plans. Initially use an oracle comparison of
validated solutions for this secondary objective; do not invent an additive
per-plan coefficient when the true loss is nonadditive.

Encode masks/pins, exact representable capacity rows, device mode exclusion and
group/domain conditions. Physical device budgets and derived storage views require
the same semantics as the ledger. Keep nonrepresentable physical conditions in
common prefix-replay validation. Exclude an invalid full decision, including
unselected unit decisions, by a complete no-good; positive dependencies make
arbitrary subset cuts unsound. `GreedyPlanner.plan` at window start cannot enumerate
all plans enabled by earlier units: use the physical search plus prefix validation.

## Starting configuration

| Parameter | Value |
| --- | --- |
| Java search visited-node budget | 100,000 per operation/window |
| Candidate host/NUMA anchors examined per unit | Up to 64, preserving incumbent/pinned hosts |
| Complete plans per unit / per window | 32 / 3,200 |
| Maximum window | 100; B=1 is the primary series |
| OR-Tools | 9.15.6755, after the shared compatibility gate |
| `num_workers` / `random_seed` | 1 / `(runSeed & 0x7fffffff)` |
| Total `max_deterministic_time` | 0.5 per operation, across all stages/restarts |
| `relative_gap_limit` / `absolute_gap_limit` | 0 / 0 |
| `enumerate_all_solutions` | false |
| Oracle-rejected solutions | At most 64 per operation |
| Native-call watchdog | 30 seconds; expiry fails the run |

Seed defaults to 42. Presolve and other solver parameters retain pinned-version
defaults. Choose each unit's deterministic anchor shortlist from baseline options,
required pins and stable seeded remaining anchors; if that unit's mandatory anchors
exceed 64, report
fallback. A pinned host still gets required NUMA alternatives within the declared
search budget. Count every expanded partial assignment against the Java budget.

Store the feasible incumbent outside CP-SAT. Accept only validated FEASIBLE/OPTIMAL
solutions. Declared budget exhaustion/UNKNOWN can retain the incumbent; invalid
models and unexpected solver failures abort. If primary admission is proven
optimal, freeze it and enumerate bounded alternative solutions for oracle loss
comparison, forbidding each complete solution after evaluation. Use the same total
budget and mark the secondary optimum unproven unless the finite space is exhausted.

CP-SAT constraints are integral. Encode a numeric row only after bounded conversion,
checked arithmetic and verified equivalence to the kernel's addition/rounding and
`fits` tolerance on this instance. Exact scaling of double values alone is
insufficient. When equivalence is uncertain, omit that row from the relaxation and
keep its exact engine-oracle check; do not round away feasible plans. Do not claim
a global gap from an incomplete pool or an optimistic
row relaxation. Sweep deterministic budget 0.1/0.5/2 and plans 16/32/128 separately;
report geometry-search gain separately from host ranking and window information.
[Solver contract](https://developers.google.com/optimization/cp/cp_solver),
[pinned parameters](https://raw.githubusercontent.com/google/or-tools/v9.15/ortools/sat/sat_parameters.proto).

## SMT reference

Optional Z3 **4.15.4**, with matching Java/native artifacts and a separate Java 25
compatibility check. Limit the first reference to **8 units, 128 plans total** and
integer miniature instances (`QF_LIA`, no quantifiers). Set `rlimit=1,000,000`,
lexicographic objectives, and an external 30-second failure watchdog. Resource-limit
UNKNOWN is not infeasibility. Use the same pool, objective and prefix oracle as CP.
Exact rational arithmetic is not automatically Java double-plus-tolerance semantics.
Compare tiny cases with exhaustive enumeration before considering a larger backend.
[Z3 parameters](https://github.com/Z3Prover/z3/blob/z3-4.15.4/src/opt/opt_params.pyg),
[objective order](https://microsoft.github.io/z3guide/docs/optimization/combiningobjectives/).
