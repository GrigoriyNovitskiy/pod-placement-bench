# Min-cost flow for a certified bulk subset

Status: planned. Use the [shared contract](README.md). Flow is an exact selector
for the network below, not an exact solver for general multidimensional placement.

## Structural gate

Require N<=100 interchangeable single-host units, fixed eligibility, ready external
dependencies and no positive inter-unit links. A retained hard constraint must
actually limit each host to at most one new replica. Do not impose that limit
artificially when the original task permits multiple replicas per host.

All coupled count limits must reduce to a certified laminar host-domain tree;
convert constant group stencils to residual replica caps as in [DP](dynamic-programming.md).
Use all relevant limits, including opaque attributes. A crossing cap, identity
asymmetry, multi-host plan or unsupported dependency selects the saved FitCount
fallback, with explicit coverage. Physical fit alone is not eligibility.

## Graph and objective

For each eligible host construct a concrete complete assignment through the common
kernel. The host edge has capacity one and scalar cost:

```text
c[h] = sum_s declaredIntegerFrequency[s] *
       (F_s(S_h) - F_s(S_h after the chosen one-replica plan))
```

This weighted-sum surrogate deliberately differs from production lexicographic
FitCount loss and from LP shadow prices. Keep that distinction in comparisons.
Use the lowest such cost among the retained geometry options for a host; omitted
geometry remains outside the optimality claim.

Build source -> eligible host -> containing domain ancestors -> sink. Domain arcs
carry residual replica caps. First obtain maximum flow F<=N; then minimize cost
for fixed F. Set a source-side capacity N. No huge penalty or floating-point
scalarization is needed to prioritize admission. Reconstruct assignments for F
actual request IDs and verify input-order prefix replay before canonical commit.

## Starting configuration

| Parameter | Value |
| --- | --- |
| Maximum replicas / host capacity | 100 / 1, certified from retained constraints |
| OR-Tools | 9.15.6755, optional backend after the shared compatibility gate |
| Copy cap / cache rows / run seed | 128 / 256 / 42 |
| Cost units | Checked signed int64; integer frequency weights, scale 1 |
| Host cutoff | None |
| Node/arc insertion | Stable host/domain ID order |
| Arc count / graph-buffer guard | 200,000 / 64 MiB |
| Workers | 1 |
| Native-call watchdog | 30 seconds; expiry fails the run |

Cost and total-cost arithmetic, native internal cost scaling and node/arc ranges
must pass checked bounds before solving. Negative fitcount losses are allowed;
never clip, wrap or rescale silently. Guard failures use the saved fallback;
BAD_COST_RANGE, invalid graph and unexpected native statuses fail the run. Only
an optimal fixed-cardinality flow proceeds as a new solution. Stable construction
provides a fixed-version tie policy; record version/platform and assignment hash.

For this surrogate, prefer the external incumbent unless admission improves or
its weighted cost decreases at equal admission. This guarantee concerns the
current restricted objective, not future replay quality. Building the sequential
incumbent costs work; include it in operation timing as in the bulk quality profile.

Do not extend this graph using independent marginal slots for arbitrary L_h(k):
flow could select a cheap later slot without its costly predecessor. Nonconvex
multi-replica choices need DP or CP/search. A chain alone does not encode general
fixed-charge costs in ordinary linear min-cost flow.

Sweep N 16/100, copy cap 64/128/512 and arc guard 200,000/1,000,000 separately.
Validate tiny graphs against enumeration, real one-per-host and laminar caps,
crossing-cap fallback, aggregate prefix validity and cost overflow. Publish fast-path
coverage; the solver's optimum is only for the certified graph and retained plans.
[Official Java/flow formulation](https://developers.google.com/optimization/flow/mincostflow),
[pinned implementation](https://github.com/google/or-tools/blob/v9.15/ortools/graph/min_cost_flow.h),
[flow scheduling](https://pdos.csail.mit.edu/papers/firmament:osdi16.pdf).
