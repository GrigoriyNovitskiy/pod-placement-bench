# CatBoost placement algorithms

All five algorithms implement `SchedulingAlgorithm` and use the common
`GreedyPlanner`, validation, atomic commit and removal accounting.

| CLI name | Java class | Selection |
| --- | --- | --- |
| `catboost-top1` | `CatBoostTop1` | Best imitation-model score |
| `catboost-topn-fitcount` | `CatBoostTopNFitCount` | Top N imitation scores, then FitCount |
| `catboost-rollout-top1` | `CatBoostRolloutTop1` | Best rollout-model score |
| `catboost-rollout-topn-fitcount` | `CatBoostRolloutTopNFitCount` | Top N rollout scores, then FitCount |
| `randomn-fitcount` | `RandomNFitCount` | Uniform N candidates without replacement, then FitCount |

`--param topN=20` sets the shortlist size for the three N variants. Top1 always keeps one candidate.
When there are at most N feasible candidates, all are passed to FitCount without
inference. RandomN has a separate seeded RNG and does not load CatBoost models.
Shortlists preserve the planner's order; equal model scores prefer the first
candidate. The existing FitCount handles all shortlist refinement and cache updates.

## Models

Two CBM files are packaged under `one/cloud/benchmark/algorithms/catboost`:

- `fitcount.cbm` learns FitCount's preferred placements.
- `rollout.cbm` learns which candidate admits more future pods in the next
  1,000 source events, including deletions. Each state checks up to four candidates
  with two Random20 + FitCount continuations. The current request is excluded from
  the reward; FitCount preference breaks equal-reward ties.

Both models use identical feature rows and groups: 1,816 states, 5,732 candidate
rows, including 188 GPU states, from datasets 02/05/07/09. States come from a
FirstFit replay. Each model is a CatBoostRanker with QuerySoftMax, 250 trees,
depth 4, learning rate 0.1 and seed 42. Parameters were fixed, without validation
checkpoint selection. Datasets 01/08/10 were excluded from fitting this pair.

The encoder retains the legacy 111-feature schema: request demands, candidate
inventory, free fractions, cluster totals and variation, NUMA, links and disk
geometry. The CBM feature names select the trained subset; no statistical scaling or
imputation is applied. Disk extent counts are expressed in the 64 MiB units used
for training, even when the input header uses a different extent size.
`request_kind` is categorical. The unencoded
`request_flags` bitmask is excluded from both models; engine constraints remain
authoritative. `models.json` records model hashes, preprocessing, training settings
and label semantics; the selected feature names are stored in the CBMs themselves.

The shared registry uses the packaged CBMs. Java constructors still accept a
`Path` for a custom model; its feature names and types must match the supported
schema. Loading or inference errors fail the run. CLI output records the SHA-256
and shortlist size from the actual CatBoost instance, including custom factories.
Each algorithm loads its model once and releases the native handle on close.

## Atomic requests and GPUs

The models were trained on singleton requests. For an atomic request, each member
is encoded separately against the same pre-placement state, and raw model scores
are summed for each complete candidate. Input demands are not summed. The
`candidate_count` feature is the number of feasible complete plans before
shortlisting. This sum is an untrained joint-ranking heuristic, not a calibrated
probability or a model trained on atomic units. Singleton feature vectors and
predictions are checked against the legacy encoder.

GPU features describe aggregate NVIDIA/Intel capacity. Concrete GPU devices and
fractional shares are selected and validated by the common engine in `devices20`
mode; the model does not observe per-device fragmentation. The algorithm respects
the selected GPU mode. It uses the existing co-host greedy planner for complete
atomic candidates and never commits members independently.

Feature extraction and native inference use batches of at most 1,024 rows, with
one shared cluster snapshot per decision.

## Run

Run through Gradle with the configured JDK 25 toolchain:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run --args="\
run --dataset /path/to/dataset \
--algorithm catboost-topn-fitcount --param topN=20 \
--param seed=44 --param fitCap=10 --param cacheRows=64 --catalogue-limit 32 \
--gpu-model aggregate --max-operations 200"
```

The operation limit makes this a small integration check. Other runner options,
GPU modes and catalogue choices follow the common runner contract.

## Historical measurements

[Comparison](../experiments/catboost/comparison.csv),
[resource utilization](../experiments/catboost/resources.csv),
[latency scopes](../experiments/catboost/latency.csv) and
[provenance](../experiments/catboost/metadata.json) retain the completed 01/08/10
experiments for these exact model hashes. Quality uses admitted pods relative to
FitCount, with shard and GPU-pod counts separate. GPU pods are included in pods.

These measurements used the previous singleton/aggregate engine, a catalogue of
32 sampled resources, copy cap 10, 64 cache rows and policy seed 44. Datasets 01/08
used one full warmup and two measured repeats; 10 used one pass without a separate
warmup, so its latency is descriptive. Utilization is sampled every 200 source
events; mean and peak are useful when the final drain leaves an empty cluster.

With N=20, the imitation model admitted 2.07–4.69% fewer pods than FitCount across
these three workloads; the rollout model admitted 1.67–6.36% fewer. Rollout helped
on 01 but worsened 08/10. Random20 + FitCount stayed within +0.16–0.79% of FitCount.
The learned variants are experimental heuristics, with no universal quality gain.

The current runner normalizes atomic lifetimes and has a different catalogue
selection rule. Historical counts and timings are not measurements of the new
atomic score-sum implementation, even with the same numeric limits. No raw
datasets, request logs or research pipeline are required for runtime inference.
