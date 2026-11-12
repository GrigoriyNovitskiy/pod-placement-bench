# Proactive ILP

`proactive-ilp` uses Geometric Best Fit for incoming requests. After every
`every` successful atomic creates, it looks for a rearrangement of active
pods that increases the number of copies of a reference CPU/RAM pod that
can fit. This follows the objective in the
[Yandex Planner talk](https://disk.yandex.ru/i/RzhVLk_qkWWBZw) at 1:14–1:17
and uses CBC through Google OR-Tools 9.15.6755.

```sh
./gradlew :one-cloud-schedulers-benchmark:installDist
./placement-bench run --dataset /path/to/dataset --algorithm proactive-ilp \
  --param seed=42 --param every=100 --param maxMoves=2 --param timeoutMs=200 \
  --param probeCpu=8 --param probeRamMiB=32768
```

The registry defaults match this example except for seed, which defaults to
44. Use `./placement-bench algorithms proactive-ilp` to inspect the parameter
ranges. The simulator exposes the same settings and shows moved pods on
their destination hosts.

The search tries up to six source hosts in descending order of free CPU and
RAM, then by host ID. For each source it considers up to 24 already placed
standalone pods without GPU demand, volume plans, a host pin or outgoing
placement dependencies. It excludes the latest admission and uses the common
dependency checks. Each movable pod has a stay option and placement options
from the shared planner on up to eight eligible destinations.

CBC chooses one real assignment per pod and an integer number of hypothetical
reference copies on each host or NUMA node. Real assignments and hypothetical
copies share the same CPU/RAM bounds. On a NUMA host, a copy debits its node and
the host. The objective gives each extra copy more weight than the entire
move budget, so ties in copy count favor fewer moves. Reference copies are
used to score the arrangement. Only real assignments enter replay state.

The engine validates the ordered relocations on a disposable copy and
recounts reference slots across the source and destinations. The first plan
that passes validation and strictly increases capacity commits atomically.
Each source allows up to three solver calls. The wall-clock budget covers the
entire search for one tick and is best effort. The neighborhood, CPU/RAM
reference profile and periodic trigger are benchmark choices.

Compare against `geometric-best-fit` with the same dataset and seed. The
report includes `proactive_runs`, `proactive_solves`, `proactive_applied`,
`proactive_moved_units`, `proactive_probe_gain`, `proactive_rejected_plans`,
`proactive_repair_nanos` and `proactive_solve_nanos`. The gain counter sums
verified increases at the times moves commit. Replay latency includes the
search and validation for the triggering create.
