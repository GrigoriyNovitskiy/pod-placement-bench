# Dynamic programming for bulk host options

Status: planned. Use the [shared contract](README.md). DP is an alternative selector
for [bulk fitcount's](bulk-fitcount.md) concrete options and frozen loss vectors,
not a second physical model or a general fleet-wide resource-discretization DP.

## Supported decomposition

Require interchangeable replicas, one-host atomic plans, ready external dependencies
and no positive inter-unit links. Local options must already satisfy NUMA/device
and local count restrictions. Joint upper limits must reduce to one laminar tree
of host sets: every pair is nested or disjoint. Verify the actual host sets,
including opaque attribute levels; level numbers alone do not prove laminarity.

For a group contributing m resources per replica to a domain, convert its remaining
resource-count limit into a replica cap floor(remaining/m); combine applicable caps.
Only do this when the group stencil makes m constant. Identity-dependent caps,
crossing domains, multi-host units and dynamic dependencies fail the gate and use
the saved FitCount solution. Do not add one-per-host or discard constraints to
make decomposition possible. Record gate coverage and fallback reasons.

## Recurrence

For independent hosts, with a concrete feasible option for every retained k:

```text
D[i,n] = min_lex over k of D[i-1,n-k] + L_i(k)
D[0,0] = zero vector; impossible states = INF
```

Choose the largest reachable n<=N, then its minimum loss. For a laminar domain
node, convolve child count arrays and keep n<=remainingReplicaCap(node). Local
geometry usage is checked inside host options. Multiple trajectories with the
same k can be reduced to one only when their retained boundary semantics agree.

Use two primitive rolling cost buffers, checked long loss accumulation and compact
predecessor counts. Recover host counts, reconstruct concrete geometry in stable
order and validate the original-order prefix replay. Keep the external sequential
incumbent and prefer it unless the declared objective improves.

## Starting configuration

| Parameter | Value |
| --- | --- |
| Replicas / exact count states | At most 100 / 0..N |
| Physical copy cap / cache rows / seed | 128 / 256 / 42 |
| Trajectories per host/count | 1 |
| Host cutoff / beam pruning | None / disabled |
| Maximum certified tree depth | 5 |
| Work budget | 100,000,000 cost-component operations |
| Optimization workspace | 64 MiB, including costs and traceback |
| Workers | 1 |

Charge vector additions, comparisons and copies to the work budget, including
allocation initialization; count transitions separately. Check workspace size
before allocation. On either guard, return the externally saved incumbent, not a
partially filled DP result. The memory limit excludes the shared model, incumbent
and baseline cache; report total process memory separately.

For catalogue length T, simple flat DP costs O(hosts*N^2*T); rolling values use
O(N*T), traceback O(treeNodes*N). Never allocate hosts*N*T. Tree convolutions have
the corresponding sum of child count-pair work. Nonconvex and negative loss curves
are permitted; do not apply greedy marginal selection or convexity shortcuts.

Sweep N 16/100, trajectories 1/4 and work 10^7/10^8 one axis at a time. Check small
cases by exhaustive enumeration, including costs A=[0,10,10], B=[0,6,12], N=2;
DP must choose both A replicas. Test laminar caps, a crossing-cap gate failure,
identity-dependent replicas, budget fallback and exact geometry restoration.
Optimality is only over the supplied options and certified decomposition.
[DP/resource allocation](https://ocw.mit.edu/courses/1-204-computer-algorithms-in-systems-engineering-spring-2010/resources/mit1_204s10_lec13/),
[multiple-choice packing](https://doi.org/10.1016/0377-0427(93)E0264-M).
