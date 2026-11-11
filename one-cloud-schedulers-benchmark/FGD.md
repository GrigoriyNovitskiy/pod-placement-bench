# Fragmentation Gradient Descent baseline

Run with the default synthetic GPU device model:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm fgd'
```

The Java entry point is `new Fgd(context, seed, catalogue)`. The catalogue is the
declared `Request[]` shared with other algorithms, prepared before replay. FGD
does not learn from future events or train a model. An explicit aggregate GPU
model is rejected because it cannot represent fragmentation between devices.

## Metric

The baseline follows the CPU/GPU metric and marginal-placement rule from
[Weng et al., USENIX ATC 2023](https://www.lingyunyang.com/assets/files/fgd-atc23.pdf).
The authors provide a Kubernetes
[score plugin](https://github.com/hkust-adsl/kubernetes-scheduler-simulator/blob/main/pkg/simulator/plugin/fgd_score.go)
and [fragmentation calculation](https://github.com/hkust-adsl/kubernetes-scheduler-simulator/blob/main/pkg/utils/frag.go).

Catalogue members are individual reference tasks. Identical CPU/GPU profiles
contribute frequency weights; an atomic unit's GPU requests are not added together
into one larger request. CPU-only members can be represented by one combined
frequency because each regards all idle GPU capacity as unusable.

For a host and reference task:

- If CPU capacity or any requested GPU type is insufficient, all idle GPU shares
  on the host are fragmented from that task's perspective.
- Otherwise, each card's idle shares are fragmented if they are too small for
  the task's per-card demand. A whole-GPU request requires entirely free cards;
  fractional leftovers on separate cards cannot be combined.
- Cards of an unrequested GPU type are also unusable for that task.
- A task without GPU demand regards all idle GPU shares as fragmented.

Sum these amounts with reference frequencies. Candidate score is the affected
host's potential **after minus before** the complete tentative placement. The
implementation uses frequency-weighted shares, omitting the common positive
normalizer of 20 shares per GPU and total reference frequency. This preserves
candidate ordering and avoids rounding to a Kubernetes integer score.

Dedicated CPU is converted with the selected host's CPU power. Separate hardware
core and nonproduction CPU budgets are checked as adaptations to this model.
RAM, network and disk fragmentation are outside this objective. Their constraints,
NUMA geometry, candidate masks, topology rules and dependencies still govern every
actual placement through the shared planner and validator.

## Candidate search

For each host/NUMA anchor, the common greedy planner supplies a complete feasible
assignment, including disks. FGD then makes one coordinate pass over fractional
GPU assignments. With other assignments fixed, it considers each card of the
required type, validates the entire atomic unit, and retains a minimum-delta
choice. It includes the original assignment, so this pass cannot worsen its
score. Whole free cards of the same synthetic type are interchangeable for this
potential; their IDs are not exhaustively permuted. Ties use the algorithm seed.

For one fractional GPU requirement, this enumerates all card choices as in the
original algorithm. Multiple GPU consumers in a compound unit use the documented
one-pass coordinate heuristic, rather than a Cartesian search over all assignments.
Disk and NUMA planning remain greedy. A planner decline is not proof of infeasibility.

An empty catalogue or a host without GPUs has zero potential. No independent CPU
packing objective is added for such ties. State is read afresh for candidate
scoring, so placements and removals require no separate fragmentation-cache
invalidation. There is no host-by-catalogue cache or unbounded assignment search.

The `devices20` topology is synthetic: real GPU models, memory limits, host sharing
modes and transcoding requirements are not reconstructed from the dataset. Results
describe this declared model. Runner metadata, scenario identity, seed, catalogue
selection and GPU assignment checksums apply in the same way as for other algorithms.
