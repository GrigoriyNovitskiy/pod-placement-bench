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

The built-ins are `fitcount`, host-residual `best-fit` and normalized residual L2
`geometric-best-fit`. [Best Fit](../algorithms/best-fit.md) ·
[Geometric Best Fit](../algorithms/geometric-best-fit.md)

The runner and CLI were independently checked before the second algorithm was added. No JMH
fixtures, automatic parameter sweep, LP solver or ML backend are introduced here.
