# Bulk fitcount

Status: implemented. Use the [shared contract](README.md). This is a service-burst
strategy; DP/search are optional ways to optimize the same host options.

## Decision and objective

Accept at most 100 already announced, interchangeable pod/regular-shard units.
The single-request overload delegates to ordinary FitCount. The first supported
bulk scope has single-host units, ready external dependencies and no positive
links between units. Unsupported bursts use sequential FitCount, with a reason. The explicit
`incumbentGuard=false` profile skips the sequential quality guard; the default
remains `true`.

Freeze catalogue scarcity order at bulk entry. Let F_s(S_h) be capped physical
multiplicity of sample s on host h. A concrete prefix of k replicas defines:

```text
L[h,s](k) = F_s(S_h) - F_s(S_h after that concrete k-replica prefix)
objective = maximize admitted replicas, then minimize sum_h L[h,s](k_h)
            lexicographically in the frozen sample order
```

This differs from sequential fitcount, whose sample order can change after each
placement. Curves can be nonconvex or nonmonotonic with greedy geometry.

## Steps

1. Preserve a sequential FitCount solution in a trial state as an external incumbent.
2. Enumerate all host/NUMA anchors once. Build concrete host-local prefix options
   for k=0..min(N, physical capacity), retaining geometry and domain usage. For
   the single default trajectory, each next copy uses the first feasible anchor
   in `numaNodes(host)` order and the planner's deterministic device order. Reuse
   the physical kernel, rather than multiplying a single plan's debit by k.
3. The initial bulk selector is greedy: choose the feasible next prefix with the
   least lexicographic marginal loss. Apply it to the trial state; update the
   affected host and test changing topology/group limits at every step. A heap
   must invalidate entries affected by those limits. Retain valid prefixes only.
4. Map interchangeable template members to actual request IDs in original order.
   Replay the chosen subset through the common engine. Compare it with the
   incumbent using the complete objective above; prefer the incumbent on a tie.
5. Commit the validated winner in input order. Forward actual placed/removed
   notifications to the persistent FitCount fallback so its cache stays current.

Stream host curves or reconstruct them; avoid an H*N*catalogue dense matrix.
Keep one wrapper and a small internal selector, without a public optimizer hierarchy.

## Starting configuration

| Parameter | Value |
| --- | --- |
| Maximum announced replicas | 100 |
| Physical copy cap / cache rows / seed | 128 / 256 / 42 |
| Host cutoff | None |
| Concrete geometry trajectories per host | 1 |
| Catalogue order | Frozen full declared catalogue |
| Equal greedy scores | Seeded choice in stable host/NUMA order |
| Sequential-incumbent quality guard | Enabled |

The enabled guard itself runs N sequential searches, so this quality profile
makes no amortized speedup claim. Run `--param incumbentGuard=false` to measure amortization without the
incumbent guarantee. In particular, the fast greedy profile can admit fewer
replicas than sequential FitCount and does not promise a global optimum. It commits
a complete validated trial proposal;
unsupported scopes and exhausted work or workspace budgets discard the trial and
use the persistent sequential fallback. Neither profile publishes partial trial
placements. The `incumbent_searches`, `unguarded_bulks`, `marginal_evaluations` and
`marginal_cache_hits` counters distinguish actual shared work from baseline work. Neither profile guarantees better
future replay quality. Sweep cap 64/128/512, trajectories 1/4 and N 16/100, one
axis at a time, with matching sequential controls.

Greedy marginals are a heuristic: A has marginal costs [10,0], B [6,6], N=2;
greedy costs 12 while selecting both A slots costs 10. [DP](dynamic-programming.md)
can optimize complete count options without a convexity assumption.

Validate aggregate resource consumption, alternative media, NUMA/domain limits,
identity rebinding and prefix replay, including fallback cache updates. Benchmark
quality separately from amortization. Batching's joint-decision and work-sharing
motivation is described in [Firmament](https://pdos.csail.mit.edu/papers/firmament:osdi16.pdf);
its flow formulation does not automatically represent our physical geometry.
