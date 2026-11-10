# Algorithm implementation plans

These are implementation plans, not implemented backends. Numerical settings are
explicit starting configurations agreed for the first experiments; none has been
selected empirically on the ten clouds. Utilization takes priority over latency.

| Plan | Primary experiment |
| --- | --- |
| [Bulk fitcount](bulk-fitcount.md) | Up to 100 announced replicas of one service; frozen-order greedy |
| [Rollout](rollout.md) | Strict online B=1; sampled mixed-demand continuation |
| [CP-SAT/search and SMT](cp-sat-search.md) | Alternative geometry for B=1; joint optimization only in declared windows |
| [Dynamic programming](dynamic-programming.md) | Exact selection of separable bulk options |
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

The minimal shared prerequisites are an engine-owned isolated trial state and
separation of fitcount selection from commit. Reuse the ledger, counters and
validation code; do not create a second authoritative validator or physical
accounting implementation, or expose mutable debit arrays. Trials isolate placements, incoming links, readiness, domain counters,
revisions, callbacks, RNG and caches. `GreedyPlanner.plan` calls `canPlace` for the
pending request: it is neither a physical-only forecast oracle nor a complete
candidate generator for later dependent units. These capabilities need actual
implementation before the new algorithms can run.

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

On a structural-gate failure or declared work-budget exhaustion, use the saved
FitCount result and report the reason and coverage. This is not permission to
remove unsupported constraints. Preserve the incumbent outside the optimizer;
check proposed replacements by isolated prefix replay before canonical commit.
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

Keep native dependencies optional and use separate benchmark JVMs. The modern
pin is OR-Tools **9.15.6755**, whose Java dependency is protobuf-java **4.33.1**;
do not force 3.25.9 underneath its generated classes. Core gencode 3.25.9 can use
a 4.x runtime under the published compatibility window, subject to a future
Java 25 compile, dataset round-trip and native-load check. Use one coherent runtime
and one OR-Tools JNI version per JVM; do not load historical DCM 9.1 alongside
modern 9.15. No solver dependency is added by these plans.
[OR-Tools pin](https://github.com/google/or-tools/releases/tag/v9.15),
[dependency](https://raw.githubusercontent.com/google/or-tools/v9.15/ortools/java/pom-local.xml.in),
[protobuf compatibility](https://protobuf.dev/support/cross-version-runtime-guarantee/).

## Implemented inventory

Checked on 2026-11-10: two production-source subclasses of `SchedulingAlgorithm`
are present and registered by the CLI:

- [FitCount](../../src/main/java/one/cloud/benchmark/algorithms/fitcount/FitCount.java):
  `--algorithm fitcount`.
- [GeometricBestFit](../../src/main/java/one/cloud/benchmark/algorithms/geometric/GeometricBestFit.java):
  `--algorithm geometric-best-fit`; see its [specification](../geometric-best-fit/README.md).

The dataset runner currently calls algorithms with B=1. `GreedyPlanner` is a
geometry helper; the runner and engine are infrastructure. All six documents
listed at the top of this index remain plans, not implementations.
