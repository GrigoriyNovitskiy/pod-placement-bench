# DCM declarative-encoding experiment

Status: planned, compatibility unverified. Use the [shared contract](README.md).
DCM compiles SQL constraints into a solver encoder; it is not a new packing score.
A handwritten CP-SAT implementation must not be reported as an implementation of DCM.

## Scope and model

Use the same finite complete-plan pool, objective, fixed old placements and prefix
oracle as [direct CP-SAT](cp-sat-search.md). Represent units, plans, physical debit
facts, inventory and group/domain facts as relations. SQL describes plan selection,
capacity restrictions and admission; the generated encoder builds CP-SAT input.
Derived host storage and device capacity must not be added as independent budgets.
Nonrepresentable physical rules retain exact engine-oracle checking and complete
solution no-goods. Enforce readiness at each input-order prefix, not just final state.

The initial comparison is B=1. Known service bulk and a B=50 reference window are
separate experiments. No preemption, migration or reordering of real commits is
inherited from the original DCM scheduler.

## Integration sequence and settings

1. Pin `com.vmware.dcm:dcm:0.15.0`, its source artifact and resolved transitive
   versions/checksums. Its reference backend is OR-Tools **9.1.9490**. Start with
   one in-memory database per run; do not add a SQL dependency to the minimal core.
2. Pass a Java 25 gate: compile/load the generated encoder, solve a one-plan case,
   load JNI on the target platform and read/round-trip existing protobuf datasets.
   Pin the actual database version in the resolved manifest. A failed gate leaves
   DCM unavailable; it must not silently substitute direct CP-SAT.
3. Upstream `OrToolsSolver.Builder` exposes worker count and integer wall seconds,
   not run seed or deterministic budget. A small, explicit generator patch must
   forward seed and deterministic time and disable ordinary wall-time stopping.
   Record the patch digest. Do not document nonexistent upstream setters.
4. Compile schema/SQL/encoder once during initialization. Populate operation facts,
   update database state, encode, solve, exchange results and validate within
   operation timing. Preserve a validated external FitCount incumbent.
5. Use a matching handwritten **direct CP 9.1.9490** control in another JVM with
   the same pool, objective and budget to study encoder overhead. Comparison with
   modern 9.15 remains useful but also changes the solver version.

| Parameter | Initial value after the gate/parameter patch |
| --- | --- |
| Primary B / reference window / service-bulk maximum | 1 / 50 / 100 |
| Anchors per unit / plans per unit / plans total | 64 / 32 / 3,200 |
| Workers / seed | 1 / `(runSeed & 0x7fffffff)`, default 42 |
| Total deterministic solve budget | 0.5 per operation across all stages |
| Java geometry search / oracle rejections | 100,000 nodes / 64 |
| External native-call watchdog | 30 seconds, run failure on expiry |
| Baseline copy cap / cache rows | 128 / 256 |

Mirror direct CP's no-good, finite-pool and incumbent rules. If the minimal patch
or matching control cannot be made to work, report an unavailable reference;
a wall-budget-only historical run is a separately labelled reproduction profile,
not the deterministic comparison. Never load historical and modern OR-Tools JNI
libraries into the same JVM.

Sweep B 1/50/100 and deterministic budget 0.1/0.5/2 with identical pools in the
matching control. Validate generated-model results on tiny enumerated cases,
positive dependencies, resource rounding and rejected-prefix examples. Check that
DB/encoder work appears in measured operation time and that setup is reported once.

The [upstream project](https://github.com/vmware-archive/declarative-cluster-management)
is archived; its published tested JDKs are 11/16, not a Java 25 compatibility claim.
The [OSDI 2020 paper](https://www.usenix.org/system/files/osdi20-suresh.pdf) motivates
joint constraint solving; its experiments are not comparisons with our fitcount.
The required parameter extension is visible in the
[upstream backend](https://github.com/vmware-archive/declarative-cluster-management/blob/master/dcm/src/main/java/com/vmware/dcm/backend/ortools/OrToolsSolver.java).
