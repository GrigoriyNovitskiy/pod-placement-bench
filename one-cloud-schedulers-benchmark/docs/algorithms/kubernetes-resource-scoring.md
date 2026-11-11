# Kubernetes resource scoring

The benchmark adapts resource score formulas from
[kube-scheduler v1.36](https://github.com/kubernetes/kubernetes/tree/v1.36.0/pkg/scheduler/framework/plugins/noderesources)
to the simulator's physical capacity model. The common planner and engine
construct and validate feasible placements.

| CLI name | Score on the 0–100 scale |
| --- | --- |
| `k8s-least-allocated` | Mean of the per-family percentages of capacity remaining |
| `k8s-most-allocated` | Mean of the per-family percentages of capacity used |
| `k8s-requested-to-capacity-ratio` | Mean of the per-family scores from a piecewise-linear utilization curve |
| `k8s-balanced-allocation` | Change in the balance of utilization across scored families after placement |

The scorer uses actual debits from the same capacity ledger as FitCount.
Host vCPU and RAM participate whenever present. Other budgets enter the score
when the plan consumes them. This includes dedicated cores, NUMA-local budgets,
network directions and queues, disk extents, spindles, bounded IOPS and GPU
shares. Device mode scores the chosen GPU cards. Aggregate mode scores the
host GPU budgets. Disk projections and unbounded IOPS are omitted.

Normalize each budget cell to its own full capacity. Average cells within a
resource family so that a budget represented at both host and NUMA levels
has one family weight. Least and Most truncate cell percentages and the final
mean. Requested-to-capacity truncates utilization and interpolation and rounds
the final mean. Its curve gives scores of 10, 100 and 50 at 0%, 85% and 100%
utilization. Balanced compares `floor(100 * (1 - populationStdDev(utilization)))`
before and after placement, using the same set of family means. The final score
is `50 + floor((50 + after - before) / 2)`. With fewer than two scored families
it is 75.

The planner tries all host/NUMA candidates and retains the first highest-scoring
plan per host. The seed breaks ties among equally scored hosts. The selected
plan commits the whole request through the common engine. FitCount uses the
same physical budgets to estimate how many copies of catalogue requests still fit.
