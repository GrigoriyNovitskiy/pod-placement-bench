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

## Optional hybrid: local DP over tentative placements

Host count contributes linearly to this count-DP; N squared, catalogue length and
physical option generation are the other costs. With 10,000 hosts and N=100,
there can be roughly 50 million count transitions before vector work. For strict
B=1, N=1 nearly degenerates to an ordinary host scan. The hybrid below applies
only to an explicitly announced bulk, never to unseen future real requests.

1. Build a feasible full-bulk tentative incumbent. All previously committed pod
   and shard placements remain fixed throughout the search.
2. Select at most 32 pending units for this pass. If there are declines, take up
   to 16 in original order, then fill with tentative assigned units on their
   eligible hosts/domains as potential blockers. Otherwise take assigned units
   in original order. Rotate the starting position on the second pass.
3. Preserve the actual assignments of every other unit as reservations in the
   search overlay. This is not `Engine.place` of later requests before earlier
   ones. The local base is the old committed state plus these reservations, each
   counted once; remove the movable units' old tentative reservations from it.
4. Include every incumbent host and actual NUMA/device plan of the movable units,
   plus required pins and deterministic diverse alternatives, up to 64 hosts.
   Select alternatives in stable seeded order with domain/resource-class coverage.
   If mandatory hosts exceed the limit, retain the incumbent and report fallback.
   Do not require a retained incumbent plan to be rediscovered by GreedyPlanner.
   Its geometry is an extra mandatory option beyond the one generated trajectory.
5. Recheck the decomposition gate relative to this boundary. Build local options
   using residual physical budgets and group/domain caps. Keep the original
   bulk-entry catalogue frequencies and scarcity order; do not resort or renormalize
   them for the smaller area. Run the same count/tree DP on movable units only.
6. Restore the complete batch and compare it with the full incumbent: maximum
   admitted units, then the original frozen lexicographic loss. Prefer the incumbent
   on a tie. Check every original-order prefix before accepting a replacement.
   Publish the final winner only after the search; individual requests still commit
   in input order. No committed business placement is rolled back.

| Hybrid parameter | Initial value |
| --- | --- |
| Movable pending units / candidate hosts | 32 / 64 |
| Passes | At most 2 |
| Total work across both passes | 100,000,000 cost-component operations |
| Total optimization workspace | 64 MiB |

The two passes share the limits; they do not each get a fresh full budget. Fixed
outside reservations and their constraints remain visible during both passes.
For 64 hosts and 32 units, the count stage has roughly 34,000 transitions before
catalogue work, excluding global planning and incumbent construction. This is a
work estimate, not measured scheduler speed. The optimum is local to the selected
area/options; preservation of the current objective is not future-trace dominance.
Crossing caps or nonseparable dependencies still select search/fallback.

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
