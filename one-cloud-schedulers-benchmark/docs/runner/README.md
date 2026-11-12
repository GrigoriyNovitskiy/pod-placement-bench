# Dataset runner design

The runner has three boundaries: `Scenario` prepares a common workload,
`DatasetRunner` executes it on a fresh engine and algorithm, and `RunnerCli`
loads files, selects a built-in algorithm and writes JSON. The programmatic runner
accepts an algorithm factory, so adding an LP or ML implementation does not require
a runner registry or a CLI dependency in the engine.

## Joint workload

Version `joint-phases-v1` accepts synthetic, empty-start operation streams. It
validates resource kinds, initial membership, generations, create/delete phases
and final drain before scheduling. Snapshot occupancy is not imported. Unsupported
snapshot/interleaved input, missing required members and joint dependency cycles
are input errors; resources are not silently dropped.

COLOCATE components are built over resources present in the initial create phase.
A component is one immutable atomic `Request`. An omitted pod does not make its
otherwise runnable storage owner disappear. Members are ordered by resource ID;
unit priority follows the first original create. Group dependency ordering counts
distinct owner units and permits a unit to supply its own group members atomically.
Shared-group ordering retains the source generator's conservative all-owner barrier.

For each churn delete phase, all touched units and their dependent closure are
removed in reverse dependency order. The paired create phase recreates the same
closed set in forward order. This deliberately expands a partial pod/shard restart
into a joint lifetime. The normalized workload, including delete IDs, is fixed
before any algorithm runs. Removing a declined unit is a scenario no-op, not a
reason to change future requests.

The scenario reports raw and normalized sizes, partial-member churn and expanded
restarts. Its versioned digest covers unit/member order, normalized events and raw
operation identity. The CLI additionally hashes both input files. Algorithm seed,
cache limits and run prefixes do not change the compiled workload.

## Measurement

Each replay creates a new `Engine`, algorithm, RNG and caches. Model/scenario
preparation and algorithm initialization are separate from operation timing.
A create measurement covers the complete `Engine.schedule` call, including
planning, validation, commit and notifications, for every attempted unit. Successful
removals are measured separately; declined-delete no-ops are counted explicitly.
Unexpected backend failures abort the run.

`initialization_total` covers runner, engine and algorithm construction;
`algorithm_initialization` is a breakdown inside that interval and is not additive.
Input hashing, loading and scenario compilation are reported separately.

Primitive latency arrays provide p50/p95/p99 after replay. Results include attempted,
admitted and declined units/resources, occupancy, candidate planner calls, sampled
heap usage and a history checksum including assignment geometry. Heap is sampled
before initialization, at occupied checkpoints and at replay end; it is not peak
RSS or retained size. A final empty world does not erase the checksum.

Quality is sampled after each create phase, before the next delete/drain phase,
and at a truncated prefix end when needed. A separate common greedy planner probes
up to 16 evenly spaced catalogue units, outside operation timing, using the same
copy cap for all algorithms. Raw physical fit counts, occupied state, probe identity
and diagnostic time are reported. They are packing diagnostics, not proofs of
feasibility or an algorithm-independent optimum.

## Reproducible selection

The default catalogue contains every initial atomic unit once. Repeated physical
shapes supply their frequencies; later churn frequency is not used. An explicit
catalogue limit selects indices `floor(i * unitCount / selectedCount)`, equally for
all algorithms and independently of their outcomes or run seed. Probe selection
uses the same rule over that declared catalogue.

An operation limit selects a prefix of normalized atomic events. It does not
rebuild group sizes, remove future resources from the static model or change the
catalogue. Limits, catalogue/probe identity, source/scenario digests, seed, Java
version and outcome counts accompany timing output. Full-catalogue fitcount can
have a substantial cold first operation; this cost remains in operation timing.

The [algorithm registry](../algorithms/README.md) lists the available strategies.
For warmed measurements and parameter sweeps, use the experiment matrix below.

## Announced service bursts

`Scenario.withServiceBursts(size)` uses `service-bursts-v2` for sizes 2..100.
It orders dependency-ready atomic units by their service's first occurrence,
then by original occurrence, and groups same-service creates into windows up to
that size. Pod plus regular shard remain one atomic unit. Only create phases
are reordered; deletes, checkpoints and the B=1 scenario keep their order.
Dependencies can split a service across several windows. This is a synthetic
service-level workload, not recovered production API history.

The reordered events, announcement boundaries and size are included in the
scenario digest before scheduling. Use the same size for every compared algorithm;
B=1 and B>1 can have different order. An operation prefix does not change the
digest. A truncated final window is explicitly reported.

The runner times one complete `Engine.schedule(window)` call. JSON format 2 keeps
attempted units and schedule-window counts separate; latency samples are whole
windows. B=1 behavior and checksum ordering are preserved. Logical requested and
admitted vectors use original allocation profiles; declined resource counts are
grouped by allocation profile. New algorithms also report static parameters, a
parameter digest and outcome/work/fallback counters.

See [bulk, DP and rollout usage](../algorithms/IMPLEMENTATION.md) for the distinct
online and announced-window profiles and explicit forecast manifest.

## Experiment matrix

Use `run` to debug one replay. Use `benchmark` for a controlled series:

```sh
./gradlew :one-cloud-schedulers-benchmark:installDist
cp one-cloud-schedulers-benchmark/docs/runner/benchmark.example.json ./local-benchmark.json
# Edit the dataset paths before running (relative paths resolve against the manifest).
./placement-bench benchmark --config ./local-benchmark.json > ./benchmark-results.json
```

The [example manifest](benchmark.example.json) runs the same scenario for each
dataset × algorithm configuration × seed. Each dataset directory needs
`snapshot.pb` and `operations.pb`. Algorithm IDs and typed parameters come
from `./placement-bench algorithms`. For Rollout, set `rolloutPrior` to the
compatible file on the algorithm object. It is also relative to the manifest.
Omit `seeds` to keep registry defaults and explicit algorithm seeds. If supplied,
the list overrides the algorithm seed where that parameter exists; algorithms
without it are run once per distinct configuration.

| Option | Default | Effect |
| --- | --- | --- |
| `forks`, `warmups`, `measurements` | 2, 1, 3 | JVMs per case; full warmup and timed replays in each JVM |
| `timeoutSeconds` | 3600 | Timeout for each worker, including startup, loading and warmup |
| `maxOperations` | 0 | Normalized event prefix; 0 means the complete replay |
| `catalogueLimit` | 0 | Deterministic sample for the algorithm and independent probes; 0 means all |
| `gpuModel`, `bulkSize` | devices20, 1 | Common GPU model and announcement window limit |
| `probeCap` | 10 | Maximum copies attempted per host and probe profile |
| `inflationAttempts`, `inflationSeed` | 1000, 42 | Fixed mixed offer count (0 disables it) and independent seed |

For a smoke run use a short prefix, `catalogueLimit: 32`, one fork and one
measurement. Keep full runs for comparisons of admission under pressure. A
shortened catalogue is part of the experiment; it can change algorithms'
decisions. A run with `warmups: 0` measures cold JVMs. Long or variable runs
can need more than one warmup. Choose fork and warmup counts after inspecting
the raw samples, and run on an otherwise idle machine. Run separate manifests
for online vs. announced-window scheduling and compare within each mode.

### What is timed

Workers run serially. A worker loads and compiles the scenario once, then each
warmup and measurement creates a new engine, algorithm, RNG and caches. This
warms the JVM while preserving the cost of populating empty algorithm caches
in each replay. Warmup results are discarded. Process startup, input work,
algorithm initialization, and all quality probes are outside replay timing.
Replay timing includes event bookkeeping, scheduling, removals and declined
delete no-ops; initialization is reported separately. Throughput is normalized
events per replay second. Create latency includes planning, validation, commit
and notifications. With `bulkSize > 1` latency is per announced window, not
per atomic unit. Successful delete latency is reported separately.

The report contains individual measurements under `cases[].forks` and
min/median/max under `cases[].timing`. The summary p95/p99 values are
distributions of the per-replay percentiles. There is no pooled percentile.
Timeouts and backend errors are recorded per fork or quality replay; they do
not count as refused requests. The CLI proceeds to other cases, emits JSON on
stdout and returns exit status 1 if any case failed. Progress goes to stderr.
Matching input hashes, scenario, options and resolved parameters identify a
comparison. A wall-clock-limited solver may yield different outcomes in timed
and quality replays; use the checksums and counts from each replay separately.

For a quick comparison of counts and latency, extract one row per case:

```sh
jq -r '.cases[] | [(.dataset | split("/")[-1]), .algorithm.id,
  .quality.counts.admittedUnits, .quality.counts.declinedUnits,
  (.timing.scheduleP95Nanos.median | if . == null then null else . / 1000000 end)] | @tsv' benchmark-results.json
```

The last column is the median per-replay p95 in milliseconds (blank if there are
no samples); check fork errors and completion status before interpreting the table.

### What quality measures

A separate replay scans the whole selected trace, including creates after
refusals. Deletes of refused units remain no-ops. Counts distinguish atomic
units (a pod and its shards may be one unit) from their individual resources.
The report also gives requested/admitted logical resource vectors and refusals
by allocation profile. Those vectors measure accumulated work across creates;
they are not instantaneous utilization.

| Report field | Interpretation |
| --- | --- |
| `checkpoints[].utilization` | Allocation/availability from the real engine ledger, per resource dimension, divided by the capacity of enabled inventory. Compare at the same event boundary and read alongside offered and admitted counts. |
| `checkpoints[].holeFilling` | How many additional copies of each of up to 16 individual profiles fit independently, using the common greedy physical planner. It tests NUMA, disk and GPU geometry, ignoring placement masks, group dependencies and other nonphysical restrictions. A nonzero `hostsAtCopyCap` means the count might be limited by the per-host cap. Do not sum profiles. |
| `inflation` | Starting at the first create-phase end (or the selected prefix if earlier), feed a fixed seeded mix into an isolated copy of the occupied engine. A common first-fit placer uses the normal constraints, commits every feasible offer and keeps going after a decline. Report attempts, admits, admits after the first refusal, and final resource utilization. |

Checkpoint boundaries are determined by the shared event stream: up to three
points around the quarters of the first fill, rounded up to complete announcement
windows, up to 32 create-phase ends and an occupied truncated prefix end, if needed.
The reported offered demand uses original requested quantities and can exceed
capacity. The physical denominator uses host ledger values for host dimensions
and enabled disk ledger values for device extents/spindles/IOPS. NUMA capacities
are not added to host capacities. Host media and device rows are alternate
views; do not sum them. A disk mode switch can make capacity unavailable
without consuming every displayed unit. Zero capacity gives a null fraction;
unbounded or unknown values give null quantities.

Inflation samples whole templates uniformly from the declared, unsampled
catalogue, retaining its shape frequencies. Clones have fresh IDs and groups,
preserve internal links, masks and pinning, and exclude templates with external
dependencies. `eligibleTemplates`, `excludedTemplates` and `sequenceChecksum`
make this restriction and the offered sequence visible. The same sequence is
offered to every algorithm on a dataset. Inflation measures the room left by
the existing placement under a common follow-up policy. A fixed budget gives
a lower bound on how much more can fit; it does not prove global saturation.
Check both admission and remaining room: a strategy that admitted fewer
original pods may trivially leave more room.

The [simulator benchmark API](../simulator.md#benchmark-api) exposes the same
quality report for the current scenario. Use the CLI for controlled timings.
