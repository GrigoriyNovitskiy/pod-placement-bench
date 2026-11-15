# Yandex Planner replay

`yp-light` runs the greedy placement policy. It prefers plans that satisfy all
soft domain limits, then minimizes the sum of `10^free` for CPU, RAM and SSD.
Each exponent is the fraction of host capacity remaining after the candidate
placement. A missing resource contributes the same value as zero free capacity.
Equal scores use the configured seed. The shared planner validates filters,
pins, NUMA, devices, and placement dependencies. The benchmark dataset does
not carry YP's hint queues or node feature scores.

`yp-planner` starts with the same policy and keeps declined requests in a
queue until they are placed or their DELETE arrives. By default it retries up
to four requests every 20 replay operations, including deletes. Each retry
opens a new scheduling window and tries light placement first. CBC then tries
to admit that request with up to two moves. Failed requests go to the back of
the queue. Every 100 operations, an independent proactive pass uses CBC to
increase the number of hypothetical CPU/RAM reference pods that would fit.
It can run with an empty queue, or after a retry pass with work still pending.

Both CBC modes use the same bounded search over source hosts, eligible
destinations, and movable standalone pods as
[proactive ILP](proactive-ilp.md). The reactive model requires one placement
for the incoming request and each moved or retained pod, and minimizes the
number of moves. The proactive model maximizes reference slots and breaks ties
in favor of fewer moves. The solver models CPU/RAM capacity on hosts and NUMA
nodes. The engine checks the complete plan, including all other resource and
placement constraints, before it applies the changes.

This is an adaptation of the [Yandex Planner design](https://habr.com/ru/companies/yandex/articles/564510/).
Replay advances on the engine's owner thread at operation boundaries. An
announced batch can cross several clock boundaries, in which case the runner
executes each due maintenance pass once after the batch. The simulator advances
after each event. The intervals, one-target reactive solve, single configurable
CPU/RAM reference profile, move budget and instantaneous simulated moves are
benchmark choices.

Late admissions are counted once against the original create and reflected in
host occupancy and deletes. Create latency measures the initial placement
attempt. `yp_maintenance_nanos` measures time spent on retries and
defragmentation, also included in replay wall time. `yp_late_admissions`,
`yp_pending`, `yp_cancelled`, `yp_reactive_applied`, and
`proactive_applied` describe the queue and committed CBC repairs.

Implement `Defragmenter.defragment(Optional<Request>)` to swap the strategy.
A present target is pending in the current engine window. An empty target
requests proactive maintenance. Construct `YpPlanner` with the strategy and
use the engine's validated commit methods for any relocations.

```sh
./gradlew :one-cloud-schedulers-benchmark:installDist
./placement-bench algorithms yp-planner
./placement-bench run --dataset /path/to/dataset --algorithm yp-planner
./placement-bench run --dataset /path/to/dataset --algorithm yp-light
```
