# Rollout over fitcount

Status: planned. Use the [shared contract](README.md). This is the primary
quality-oriented strict B=1 experiment, with no waiting for future real requests.

## Decision

For a current complete feasible plan a, estimate:

```text
Qhat(a) = mean over M common scenarios of
          admitted future atomic units during H continuation steps
```

Continuation uses the existing FitCount. Equal-unit admission is the initial
explicit surrogate; it is not a scalar optimum of CPU, RAM and storage utilization.
Publish logical resource vectors and class refusals, and select configurations by
the shared workload-inflation protocol. Do not reward physical debit or add a
voluntary rejection action when a feasible current plan exists.

1. Obtain the ordinary FitCount winner and the same complete candidate geometry
   used by the baseline. Keep the winner and seven distinct-host candidates,
   selected deterministically by a seeded permutation of the feasible host IDs;
   choose one anchor per selected host in stable order. Deduplicate assignments.
2. Generate M common synthetic scenarios from the required prior manifest below.
   For each candidate, apply it in an isolated trial and replay those scenarios
   with FitCount.
3. Use the same exogenous events and continuation seeds for every candidate.
   Choose largest Qhat; on a tie prefer the original FitCount winner, then the
   stable candidate order. Commit one actual request exactly once.

A separate candidate-diversity ablation may stratify by eligible resource class or
domain. Keep the actual candidate pool fixed when attributing a gain to ranking.

## Forecast and isolation

The first experiment is saturation-only: H synthetic creates and no hypothetical
releases, even when the real replay later contains removals. This is an explicit
forecast assumption, not a hidden runtime load trigger. A lifetime/churn forecast
is a separate model and experiment.

Require a versioned `rollout-prior-v1` manifest: canonically ordered bundle/template
IDs, positive integer weights, complete ordered dependency closures, declared
virtual group cardinalities, identity-rebinding rules and a digest. Fresh virtual
resources/internal groups and rebound internal links are explicit; external links,
host pins and masks retain only the references declared by that manifest. There
is no implicit shape-only forecast.

The first sampler draws bundles IID with replacement, with mass proportional to
their integer weights. Use `SplittableRandom(scenarioSeed).nextLong(totalWeight)`
and cumulative weights in manifest order; reject an overflowing total weight.
Every bundle must have 1..H atomic creates after closure. Any larger entry makes
the prior unsupported for this H: report fallback instead of dropping the entry.
For each draw, append its whole bundle if it fits the remaining create budget;
otherwise end that scenario. Do not resample or truncate. At most H draws occur;
report each path's effective length, which is at most H.

A physical shape catalogue is insufficient. Build a scenario-side specification
with fresh virtual resource/group IDs, complete dependency closures, masks, pins,
internal links and shared-storage owner semantics. Preserve real group cardinalities;
never append virtual members to canonical groups. Count complete atomic creates
in H under the boundary rule above. Unsupported forecast structures use
an explicit FitCount fallback; malformed scenarios fail the run.

This requires an engine-owned trial facility and a declared virtual-instance bank.
Calling `canPlace` on unannounced real IDs is not a substitute. Static templates
may be prepared once; scenario generation, branching and all continuation work
performed per request belong to operation timing. Trial policies have private
caches/RNG and cannot notify or advance canonical state.

## Starting configuration

| Parameter | Value |
| --- | --- |
| Current candidate limit K | 8, including the baseline |
| Common scenarios M | 8 |
| Future atomic creates H | 8 |
| Terminal bonus / adaptive early stopping | 0 / disabled |
| Continuation copy cap / cache rows | 128 / 256 |
| Workers / run seed | 1 / 42 |
| Seed derivation | Fixed SplitMix64 mixing below |

Define `mix64(z)` by the following [SplitMix finalizer](https://prng.di.unimi.it/splitmix.c),
using unsigned shifts and wrapping 64-bit arithmetic:

```text
z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9
z = (z ^ (z >>> 27)) * 0x94d049bb133111eb
return z ^ (z >>> 31)
```

`operationIndex` is the zero-based attempted real-create index, including declined
creates; `scenarioIndex` is zero-based within this decision. Set
`r = mix64(runSeed ^ mix64(operationIndex))`,
`scenarioSeed = mix64(r ^ mix64(scenarioIndex))`,
`policySeed = mix64(scenarioSeed ^ 0xD1B54A32D192ED03)`.
Candidate identity is absent from these seeds. Keep real-policy RNG separate.

The default costs up to 8*8*8 = **512 full-cloud continuation schedule calls** per
real decision, in addition to candidate planning. Start with a common 200-operation
pilot prefix; record the prefix and catalogue independently. No fast-path claim is
made. Sweep K 8/16, M 8/32 and H 8/32 one axis at a time; test a shifted demand mix.

Check isolation and the exact scalar counterexample: free capacities (2,3), current
size 1, next three IID sizes 2/3 with equal probabilities. Fitcount chooses (1,3),
expected future admissions 1 and useful work 2.5; (2,2) yields 11/8 admissions
and useful work 2.75. This proves a possible gain,
not cloud-level dominance. Baseline inclusion guarantees only a non-worse sampled
Qhat; Monte Carlo error, horizon truncation and forecast error can reverse it.
The adaptation follows [stochastic rollout](https://web.mit.edu/dimitrib/www/quiz.pdf),
with constrained variants discussed by [Bertsekas](https://arxiv.org/html/2002.07407v1).
[Prediction-aware packing](https://arxiv.org/pdf/2102.03311) motivates testing error
sensitivity; its one-dimensional guarantees do not transfer to our constraints.
