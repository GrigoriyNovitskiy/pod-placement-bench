# Algorithm implementation plans

These documents define implementation contracts and planned experiments.
Bulk fitcount, count/tree DP and rollout are implemented; see
[implementation and usage](IMPLEMENTATION.md). CatBoost and RandomN shortlist
algorithms, packaged models and historical measurements are described in the
[CatBoost guide](catboost.md).

Numerical settings below are
explicit starting configurations agreed for the first experiments; none has been
selected empirically on the ten clouds. Utilization takes priority over latency.

| Plan | Primary experiment |
| --- | --- |
| [Bulk fitcount](bulk-fitcount.md) | Up to 100 announced replicas of one service; frozen-order greedy |
| [Rollout](rollout.md) | Strict online B=1; sampled mixed-demand continuation |
| [CP-SAT/search and SMT](cp-sat-search.md) | Alternative geometry for B=1; joint optimization only in declared windows |
| [Dynamic programming](dynamic-programming.md) | Separable bulk options; optional 32-unit/64-host local hybrid |
| [DCM](dcm.md) | SQL-generated encoding experiment, using a matching direct-CP control |
| [Min-cost flow](min-cost-flow.md) | Certified one-replica-per-host bulk with laminar domain limits |

Bulk fitcount defines a scoring strategy; DP and flow are selectors for restricted
models. DCM generates a solver encoding. These names do not imply six unrelated
mathematical heuristics or six implemented `SchedulingAlgorithm` classes.

## Shared contract

Use `SchedulingAlgorithm`, `SchedulingContext`, `Request`, `Assignment` and the
existing engine. Preserve layouts, stripes, metadata, whole-disk and IOPS budgets;
resource classes, NUMA and dedicated resources; filters, pins, topology limits,
affinity, dependencies and regular/shared-storage semantics. `CapacityLedger` and
the common validator remain authoritative. Host storage projections and physical
device capacity are coordinated views, not independent supplies.

Committed placements stay fixed. Strict B=1 sees only the current request and its
declared prior. Bulk/windows see only explicitly announced requests. Commit a
chosen subset in input order, with a valid state at every prefix. A request is
atomic; its wrapper alone does not require all members to share a host.

Trials execute scheduler and accounting logic only. They do not run pod application
code, start/stop containers or shards, send minion/provisioning RPC, or invoke real
business lifecycle effects. Backtracking changes only unpublished tentative plans.
Already committed placements are constants; search never undoes their commit. In
production integration, publish the chosen placement once before normal lifecycle
processing. The benchmark itself executes only a resource/state model.

The minimal shared prerequisites are an engine-owned isolated trial state and
separation of fitcount selection from commit. Reuse the ledger, counters and
validation code; do not create a second authoritative validator or physical
accounting implementation, or expose mutable debit arrays. Trials isolate
placements, incoming links, readiness, domain counters,
revisions, callbacks, RNG and caches. `GreedyPlanner.plan` calls `canPlace` for the
pending request: it is neither a physical-only forecast oracle nor a complete
candidate generator for later dependent units. These capabilities are implemented by `Engine.Trial` and `FitCount.select`;
the common physical kernel remains authoritative.

The common comparison profile uses the full declared catalogue, physical copy
cap **128**, cache rows **256**, seed **42** and one worker. This does not change
FitCount's existing default cap 10. Compare cap 64/128/512 separately: copy cap
changes scores; cache-row eviction should change time only. Record every limit,
ordering rule, dataset/scenario digest, backend version and parameter digest.

For bulk, declare the service burst in the scenario and version its transformation.
For current exporter v2, the nonzero POD group identifies an anonymized service
when a unit contains exactly one POD. It does not establish interchangeability:
check allocation/geometry, masks, pins, relative links, group stencils and external
dependencies. Never manufacture a burst by reading the hidden replay suffix.

On a structural-gate failure or declared work-budget exhaustion, bulk/DP discard
the detached trial and run sequential FitCount, reporting the reason and coverage.
Unsupported constraints must remain enforced. Check proposed replacements by
isolated prefix replay before canonical commit.
Unexpected exceptions, cancellation, invalid models, native failures and watchdog
expiry fail the run. They are not ordinary declines. A bound for a restricted
candidate pool is not a global bound for the full placement problem.

## Evaluation and dependencies

Compare workload inflation at fixed fleet, workload mix and class-refusal policy;
report accepted logical resource vectors and refusals by class. For churn, fix
lifetimes and delete IDs independently of admissions. Physical debit is diagnostic:
extra RAID copies or wasteful whole-disk consumption must not earn useful-work
reward. This follows the evaluation problem described by
[Google's packing study](https://john.e-wilkes.com/papers/2014-IEEECluster-job-packing.pdf).

Count generation, trial replay, cache work, SQL, encoding, solving, validation and
commit inside operation timing. Report one-time initialization separately. Freeze
parameters on three clouds, then evaluate seven held-out clouds with at least
three seeds, showing per-cloud results and a macro-average. Change one tuning axis
at a time. Publish the actual scoring surrogate as well as this quality protocol.

[Proactive ILP](proactive-ilp.md) loads CBC when selected. The module uses
OR-Tools **9.15.6755** and its protobuf-java **4.33.1** runtime with the
existing lite classes generated by protoc **3.25.9**. Use separate JVMs
for experiments with different native backends, including historical DCM 9.1.
[OR-Tools pin](https://github.com/google/or-tools/releases/tag/v9.15),
[dependency](https://raw.githubusercontent.com/google/or-tools/v9.15/ortools/java/pom-local.xml.in),
[protobuf compatibility](https://protobuf.dev/support/cross-version-runtime-guarantee/).

## Implemented inventory

The CLI includes the following implementations (plus the four Kubernetes scoring
modes):

| CLI mode | Implementation |
| --- | --- |
| `fitcount` | [FitCount](../../src/main/java/one/cloud/benchmark/algorithms/fitcount/FitCount.java), lexicographic selection |
| `mean-fitcount` | The same class, `FitCount.weighted` |
| `geometric-best-fit` | [GeometricBestFit](../../src/main/java/one/cloud/benchmark/algorithms/geometric/GeometricBestFit.java); [specification](../geometric-best-fit/README.md) |
| `fgd` | [Fgd](../../src/main/java/one/cloud/benchmark/algorithms/fgd/Fgd.java), GPU fragmentation |
| `cpu-ram-fgd` | The same class, `Fgd.cpuRam` |
| `bulk-fitcount` | `BulkFitCount`, frozen-order greedy selection |
| `dp` | `BulkFitCount.dynamicProgramming`, count/tree selection |
| `rollout` | `Rollout`, explicit prior and common isolated continuation paths |
| `catboost-top1` / `catboost-topn-fitcount` | `CatBoostTop1` / `CatBoostTopNFitCount`, imitation model |
| `catboost-rollout-top1` / `catboost-rollout-topn-fitcount` | `CatBoostRolloutTop1` / `CatBoostRolloutTopNFitCount`, trained rollout model |
| `randomn-fitcount` | `RandomNFitCount`, seeded shortlist then FitCount |

The runner defaults to B=1 and accepts explicit versioned service-burst windows. `GreedyPlanner` is a
geometry helper; the runner and engine are infrastructure. The optional local DP hybrid and the CP-SAT/DCM/min-cost-flow backends remain
plans. Bulk fitcount, count/tree DP and rollout use the shared engine trials.
The CLI additionally exposes the four Kubernetes resource-scoring modes.
