# CP-SAT, bounded search and an SMT reference

Status: CP-SAT and general physical search are planned. The bounded CPU/RAM SMT
reference is implemented as `smt`; see below. Use the [shared contract](README.md). For B=1 the primary gain is
finding alternative feasible NUMA/device geometry. An admission solver alone does
not predict future requests. Bulk and other announced windows are separate series.

## Search and model

Backtracking reverts tentative resource reservations and assignments of pending
requests only. It never evicts an already running pod or moves a committed shard.
No search branch starts containers, sends real minion RPC or executes business
lifecycle effects. Keep old placements fixed; publish only the final validated
choice, after which normal lifecycle processing can begin.

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
exceed 64, report fallback. A pinned host still gets required NUMA alternatives within the declared
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
a global gap from an incomplete pool or an optimistic row relaxation. Sweep deterministic budget 0.1/0.5/2 and plans 16/32/128 separately;
report geometry-search gain separately from host ranking and window information.
[Solver contract](https://developers.google.com/optimization/cp/cp_solver),
[pinned parameters](https://raw.githubusercontent.com/google/or-tools/v9.15/ortools/sat/sat_parameters.proto).

## SMT reference

The `smt` algorithm runs Z3 as an optional executable on `PATH`, using SMT-LIB2
and a separate process for each supported compound or multi-unit window. A supported
single-unit, single-member window uses the exact FitCount fast path described below.
The backend was verified with **Z3 4.15.2** on Java 25. Its actual version is included in runtime
parameters. A missing executable uses the saved FitCount result and increments
`fallback_solver_unavailable`. Install Z3 before comparing this algorithm:

```sh
z3 -version
./placement-bench algorithms smt
./placement-bench run --dataset /path/to/dataset --algorithm smt
./placement-bench run --dataset /path/to/dataset --algorithm smt --bulk-size 8
```

This first reference supports at most **8 units, 128 total members and 128 complete
plans**. Requests may consume CPU and RAM and have no disk requirements. CPU/RAM
amounts in the entire model, including inventory and unused catalogue profiles,
must be nonnegative integers at most 2^48. Current residual capacity and summed
window demand are checked against the same range. This conservative gate makes
integer capacity rows agree with the ledger's double arithmetic and four-ULP
tolerance. Other models or exhausted candidate budgets retain sequential FitCount
and report their fallback reason.

Candidate generation visits host/NUMA choices for each member in stable order,
retains masks and pins, and includes every saved incumbent plan. A compound request
can use different hosts or NUMA nodes. Complete plans use the ledger's physical
CPU/RAM projection. Group limits, colocation, readiness and other policy conditions
are evaluated by input-order replay in an isolated engine trial. Generating physical
plans before policy checks allows an earlier unit to enable a later dependency.
Committed placements stay fixed throughout this algorithm.

For a window containing one CPU/RAM member, the decision-local FitCount baseline
already examines every host/NUMA assignment. There is no additional physical
geometry to search, and minimizing its per-host FitCount loss is equivalent to
maximizing the combined residual score. Such windows retain that baseline without
building another planner or starting Z3, and increment `singleton_optima`.
The seed, final prefix replay and canonical commit are preserved, including when
no placement is feasible. Compound atomic requests still use the solver at B=1.

Z3 selects at most one plan per unit and maximizes admitted units using QF_LIA.
Each complete decision, including skipped units, is blocked after evaluation.
Valid solutions are compared using frozen catalogue order and physical FitCount
on the combined residual state. The saved incumbent wins ties. Resource-limit
exhaustion retains the original incumbent. Reaching the solution-count budget
keeps the best validated solution encountered. A `pool_optima` count means all
remaining decisions with at least the winning admission were exhausted. This
claim covers the generated pool and its objective.

The default budgets are **100,000 candidate search nodes**, **64 checked solutions**
and **rlimit=1,000,000 across all solver checks in a window**. CLI parameters
`searchNodes`, `solutions` and `rlimit` expose these budgets. The physical copy cap
is 128, cache rows 256 and seed 42. A decision-local FitCount trial uses the next
seed from the algorithm's seeded random source. Runtime counters report candidate
work, optimizer calls, rejected solutions, improvements, exhausted budgets and
fallback reasons. The runner includes runtime counters in quality-enabled runs.

A 30-second absolute watchdog terminates the solver process and fails the operation.
Malformed output, unexpected UNKNOWN reasons and solver failures also fail the run.
Responses are size-bounded and every solver process is closed after its window.
Candidate generation, baseline trials, scoring, process startup, solving, validation
and commit are included in operation timing. Startup cost and bounded exhaustive
comparison make this a small-instance reference implementation.

Tests compare miniature admission results with exhaustive enumeration and exercise
multi-host atomic units, NUMA/RAM geometry, pinned requests, positive dependencies,
crossing domain limits, combined FitCount scoring and numerical/budget fallbacks.
Tests requiring the optional executable are skipped when Z3 is absent.
[Z3 SMT-LIB interface](https://microsoft.github.io/z3guide/docs/logic/basiccommands/),
[objective order](https://microsoft.github.io/z3guide/docs/optimization/combiningobjectives/).

### Performance and bounded-search tuning

Use the isolated-JVM benchmark runner, with the same seed, copy cap, catalogue,
cache rows and announcement size for every compared algorithm. Inspect
`singleton_optima`, `optimized_windows` and `fallback_*` before interpreting the
results: a fast fallback run does not measure Z3. In particular, whole-model
fractional CPU/RAM values can cause every window of a production dataset to use
FitCount. The single-member fast path proves its result without Z3.

For a latency-oriented experiment, start with `--param solutions=1`. This keeps
the first complete solver proposal if validation and the score accept it, and
retains the incumbent otherwise. It does not exhaustively optimize the secondary
FitCount objective. Lower `--param rlimit` separately if needed and check the
`resource_budget` counter; reaching that budget restores the original incumbent,
so additional search does not imply monotonically better replay outcomes. The
reference defaults remain unchanged.

A small local comparison on Java 25 / Z3 4.15.2 used four CPU/RAM hosts, 24 units,
two create/drain cycles (96 events), with and without two NUMA nodes per host.
Settings were seed 42, copy cap 10, cache rows 64 and a 16-unit catalogue, with
two serial JVM forks, one warmup and three measurements per fork. The single-member
fast path preserved complete placement checksums while reducing B=1 replay time
from about 0.40–0.50 seconds to a few milliseconds. At B=8, changing `solutions`
from 64 to 1 reduced replay time from about 0.8 seconds to 0.2–0.4 seconds; both
settings admitted 28 of 48 offered creates on these fixtures. The admitted workload
mix and residual placement quality can differ despite equal counts. Seeds 42 and 44
were checked for the budget sweep. This is a local trade-off measurement, not a
general admission or optimality guarantee. FitCount and the other simple
heuristics took about 1–3 milliseconds on these tiny fixtures. Production-sized
solver performance and broader resource support require separate work.
