# Kubernetes resource scoring

These strategies use the resource score formulas from
[kube-scheduler v1.36](https://github.com/kubernetes/kubernetes/tree/v1.36.0/pkg/scheduler/framework/plugins/noderesources).
They run independently through the benchmark engine. CPU is the host's vCPU
budget, and memory is its RAM budget. Scores use actual capacity remaining after
a feasible plan, including dedicated-core conversion and RAM used by memdisks.
GPU, NUMA, storage, network and placement constraints still affect feasibility.

| CLI name | Score on the 0–100 scale |
| --- | --- |
| `k8s-least-allocated` | Mean percentage of CPU and RAM remaining |
| `k8s-most-allocated` | Mean percentage of CPU and RAM used |
| `k8s-requested-to-capacity-ratio` | Mean score from a piecewise-linear curve applied separately to CPU and RAM usage |
| `k8s-balanced-allocation` | Improvement in CPU/RAM balance after placing the request |

CPU and RAM have equal weight. An absent dimension is skipped. Least and Most
truncate resource percentages and the final mean. Requested-to-capacity
truncates utilization and interpolation and rounds the final mean. Its fixed
experimental curve gives scores of 10, 100 and 50 at 0%, 85% and 100%
utilization, respectively. Balanced uses the change in the truncated
`100 * (1 - abs(cpuUsedFraction - ramUsedFraction) / 2)` score. A host with fewer
than two scored dimensions gets the neutral score of 75.

The planner tries all host/NUMA candidates and retains the first highest-scoring
plan per host. The seed breaks ties among equally scored hosts. The selected
plan commits the whole request through the common engine.
