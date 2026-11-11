# Bulk fitcount, count DP and rollout

The three implementations use the existing validator and physical geometry.
`FitCount.select` returns an owned plan without committing it; its ordinary
`schedule` behavior is unchanged. `SchedulingContext.trial(model)` creates an
engine-owned detached state with `state()` and `reset()`. One snapshot serves an
entire decision. Reset restores touched physical cells exactly, including floating
values, host projections and GPU shares. Trials have private policy/cache/RNG and
never notify canonical algorithms. Nested trials and stale sources are rejected.

## CLI

Run from the repository root with Java 25:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm bulk-fitcount --bulk-size 100 --seed 42'
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm dp --bulk-size 100 --seed 42'
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm rollout --rollout-prior /path/to/prior.pb --max-operations 200 --seed 42'
```

New modes default to copy cap 128 and cache 256; existing modes keep cap 10.
No catalogue limit is applied by default. An explicit `--catalogue-limit` is
reported and should match between controls. `--bulk-size` defaults 1; without an
announced burst bulk/DP delegate singleton requests to ordinary fitcount.

`service-bursts-v1` groups only consecutive creates with the same nonzero service
group and exactly one POD per atomic unit, up to the declared size 1..100. It does
not reorder events and breaks at deletes/checkpoints. Its full window boundaries
are included in the scenario digest before replay. All algorithms can receive the
same announcements; a normal FitCount handles the array sequentially. A prefix
may cut the last window; this is explicitly reported. Rollout requires B=1.

JSON format 2 reports attempted units separately from `schedule_windows`. Schedule
latency measures one complete announced window, with no division or duplicated
per-unit samples. `algorithm_parameters` and their deterministic parameter digest
identify the profile; `algorithm_counters` include coverage/fallbacks and work.
Logical resource vectors sum original allocation profiles, so RAID overhead and
whole-disk waste earn no extra useful-work credit. Refusals by allocation profile
are resource counts, including every member of a declined atomic unit.

## Bulk and DP

`BulkFitCount` implements frozen-order greedy marginals. Its
`dynamicProgramming(...)` factory selects count/tree DP over the same concrete
host trajectories. A saved sequential FitCount trial is the external incumbent:
maximize admitted units, then frozen lexicographic physical-fit loss; it wins ties.
The incumbent uses a declared decision-local SplitMix seed rather than pretending
to clone the persistent fallback's SplittableRandom. Canonical placed/removed
notifications keep the persistent fallback cache current.

The structural gate certifies allocation/geometry, masks/pins, relative links,
shared/private group stencils, ready external dependencies and one-host options.
Unsupported bursts retain the sequential result with a reason. DP additionally
certifies actual laminar host sets, constant per-replica group multiplicities and
a maximum depth 5. NUMA and host-local limits remain in concrete geometry; a NUMA
cap is never collapsed into an artificial host cap.

Count DP retains nonconvex and negative loss curves. It uses rolling primitive
vectors and compact traceback, with 100,000,000 component operations and 64 MiB
optimizer workspace guards. No hosts*replicas*catalogue cost matrix is retained.
Guard exhaustion returns the saved incumbent, not a partial DP solution. Common
trial/engine/planner storage and external incumbent/cache are reported through
process heap diagnostics in addition to the optimizer's own workspace accounting.
The optional 32-unit/64-host local hybrid from the proposal is not implemented.

## Explicit rollout prior

`src/main/proto/rollout_prior.proto` defines a separate manifest; the dataset wire
format is unchanged. Write one ordinary, non-delimited `RolloutPriorProtos.Prior`
message to `prior.pb`. Its version is `rollout-prior-v1`, `dataset_id` must match
the dataset, and `sha256` is the 32-byte digest returned by
`RolloutPrior.canonicalDigest(prior)` before filling the `sha256` field. This is a
content checksum, not an authentication signature.

Bundles and units have strictly increasing positive IDs. Members are ordered by
source resource ID and declare the original candidate-set and pinned-host values
(zero has the dataset meaning). Each bundle has a positive integer weight; the
sum must fit a signed long. Every nonzero member group has a listed internal
group with its full original cardinality and complete source membership. Internal
resource/group references are rebound to fresh IDs. External resource/group IDs
are explicitly listed in increasing order and retain their canonical references.
Unused or undeclared external references are invalid.

A minimal group-free template can be written from Java as follows (use real IDs
and the source mask/pin values from the model):

```java
final var unsigned = RolloutPriorProtos.Prior.newBuilder()
        .setVersion(RolloutPrior.VERSION).setDatasetId(model.id)
        .addBundles(RolloutPriorProtos.Bundle.newBuilder().setId(1).setWeight(1)
                .addUnits(RolloutPriorProtos.Unit.newBuilder().setId(1)
                        .addMembers(RolloutPriorProtos.Member.newBuilder()
                                .setSourceResource(resource)
                                .setCandidates(model.rules.candidates[model.constraints[resource]])
                                .setPinnedHost(model.rules.pinnedHost[model.constraints[resource]]))))
        .build();
final var prior = unsigned.toBuilder()
        .setSha256(ByteString.copyFrom(RolloutPrior.canonicalDigest(unsigned))).build();
Files.write(path, prior.toByteArray());
```

For services or storage owners, explicitly declare all members of each internal
group and order the atomic creates by their dependencies. Incomplete closures or
bundles larger than H are unsupported and cause a reported FitCount fallback;
invalid references/cardinalities/digest/weights fail the run. Groups are never
silently shrunk. The cold virtual bank extends only the trial model; canonical
group cardinalities and the real cluster model stay unchanged.

Defaults are K=8/M=8/H=8 with one worker. CLI options `--rollout-candidates`,
`--rollout-scenarios`, `--rollout-horizon` select an explicit smaller pilot or
parameter experiment. These paths are IID weighted whole bundles with replacement;
an overflowing last bundle ends the path without truncation or resampling.
Each path uses distinct virtual identities. The exact specified SplitMix seeds
and exogenous paths are common across candidates. Counters report each path's
effective create length. The score is sampled admitted future units, not physical
debit. The baseline is included and wins ties.

The default profile can perform 512 full-cloud continuation schedules per real
request, plus planning and private-cache work. All of that work stays inside
schedule timing. Start functional checks with explicitly reduced K/M/H and a
small prefix/catalogue; these are validation limits, not an implicit runtime cutoff.
No parameter sweep, train/holdout evaluation or general quality advantage is
implied by implementation and diagnostic pilots.
