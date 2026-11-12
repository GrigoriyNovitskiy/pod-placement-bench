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


## Implementation and reproduction contract

Status: implemented reference behavior. This section is the maintenance/porting
specification for the **four ML modes** above; RandomN is their non-ML control.
Preserve historical measurements and model provenance. No runtime changes or
new training are implied by adding this contract. The
[three classical plans](hybrids.md) and [two new ML plans](ml-hybrids.md) are
separate, not implemented replacements for these modes.

### Registry parameters

| Modes | Accepted CLI parameters | Defaults |
| --- | --- | --- |
| `catboost-top1`, `catboost-rollout-top1` | None | N=1; internal seed44/cap10/cache64 |
| `catboost-topn-fitcount`, `catboost-rollout-topn-fitcount` | topN, seed, fitCap, cacheRows | 20, 44, 10, 64 |

Top-N ranges are the existing descriptors: topN1..1000, fitCap1..100,
cacheRows1..4096, seed within ±9007199254740991. The registry has no `model`
parameter for these CatBoost modes. Java constructors accepting Path support
custom factories; convenience constructors can use cacheRows256. Constructor
convenience settings and the historical training seed42 are not CLI defaults.

### Candidate and scoring pipeline

1. Reuse `FitCount` with `CandidateSelector` and the common `GreedyPlanner`.
   Candidates are complete same-host plans in canonical host/NUMA order,
   including anchor0. C counts **plans**, not distinct hosts. Each plan has
   request.size() member assignments; members are never committed separately.
2. C=0 causes an ordinary decline. C=1 reaches the delegate's direct path.
   At C<=N retain all plans without feature extraction/inference. The model
   is nevertheless loaded and checked once during algorithm construction.
3. Otherwise prepare one pre-placement feature Decision; encode candidate-major,
   member-major rows. Infer in batches of at most1024 rows, which can cross a
   candidate boundary. For candidate i accumulate raw scores in member order:

   ```text
   score[i] = sum(member in request, nativeModelScore(features[i,member]))
   ```

4. Select largest score first, using existing Double.compare ordering; exact
   equal scores prefer lower original candidate index. There is no epsilon or
   softmax calibration here. Return the chosen original indices sorted ascending
   to the unchanged FitCount lexicographic refiner and its final seeded tie-break.
5. Forward real placed/removed notifications to the one delegate. Native model
   handle is owned by the adapter lifetime and released on close; loading,
   unsupported model/features and non-finite per-member inference errors fail
   the run. They are not ordinary decline or fallback to RandomN.

With the same model/planner/current state, N=1 has the corresponding Top1
selection. Full-replay equality to ordinary FitCount requires N>=C at **every**
decision and the same seed/cap/cache/catalogue, geometry and callback lifecycle.
Increasing the candidate pool does not prove better future replay quality.
`rollout` in the model name does not execute runtime continuations or consume
hidden future operations.

### Feature and artifact compatibility

The authoritative ordered111-feature schema is
[`CandidateFeatures.names()`](../../src/main/java/one/cloud/benchmark/algorithms/catboost/CandidateFeatures.java).
It consists of12 scalar fields followed by11 resource dimensions with9 fields
each. Dimensions in order: vcores, mem_mib, lan_out_mbit, lan_in_mbit, nvme_gib,
ssd_gib, hdd_gib, nvidia_gpus, nonprod_vcores, hw_cores, intel_gpus. Per dimension:
request, host_free, host_capacity, host_free_fraction, request_over_host_free,
anchor_free, cluster_free, cluster_free_fraction, cluster_free_fraction_std.

`host_capacity` and fraction denominators are **context.available captured at
encoder construction**, not freshly reconstructed hardware totals. Construct
one encoder for the canonical algorithm lifetime. Refresh cluster statistics
once per decision and encode all members against that same pre-placement state.
`candidate_count` is full C before shortlisting. ratio(v,d)=v/d when d>0, else0;
std is population std over all hosts' free/initial fractions. Preserve float rows,
feature order, selected-device deduplication, extentBytes/64MiB conversion,
known-IOPS log1p and explicit unknown-IOPS counts from the current encoder.
Do not replace unknown measurements with arbitrary learned imputation.

[`CatBoostScorer`](../../src/main/java/one/cloud/benchmark/algorithms/catboost/CatBoostScorer.java)
selects a model's supported subset of names/types: request_kind is categorical,
encoded as a nonnegative integer decimal string; other supported fields are
numeric. The packaged models omit request_flags; this is their training choice,
not a universal prohibition on supported custom numeric features. Require scalar
prediction dimension, valid unique per-type indices, supported names/types and
finite correctly sized input rows. Inference uses pinned Java/native dependency
1.2.8; do not infer the historical training-library version from this pin.

Model hashes, training settings and label semantics are recorded in
[`models.json`](../../src/main/resources/one/cloud/benchmark/algorithms/catboost/models.json).
The known recipe is QuerySoftMax,250trees,depth4,learningRate0.1,seed42,CPU4threads,
no validation checkpoint selection. Fitcount labels imitate teacher preference;
rollout labels evaluate up to4 candidates using2 continuations of1000 **source
training events**, including deletes, excluding current request from reward.
Those future-derived labels belong to offline training only. Runtime inputs
remain the current announced request and current state.

This checkout supplies CBMs, metadata and runtime, not a complete raw-state/label
construction and fitting pipeline. Metadata hashes/recorded settings alone do
not reproduce weights. Before claiming retraining reproducibility, provide
source data/split hashes, exact teacher/candidate policy and tie semantics,
label generator, training dependency version/config and export/inference parity.
Do not invent missing scripts or runnable commands. Preserve train02/05/07/09
and the documented fitting exclusions; evaluating on01/08/10 repeatedly does not
make them newly untouched holdouts.

`run` CLI records the actually loaded CatBoost SHA/topN through
CatBoostAlgorithm accessors. Current benchmark matrices do not automatically
inherit that run-only special handling; freeze actual CBM hashes and code revision
in experiment provenance rather than claiming a nonexistent report field.

### Acceptance requirements for subsequent changes

Reuse existing [CatBoostTest](../../src/test/java/one/cloud/benchmark/algorithms/catboost/CatBoostTest.java),
FitCount/engine/CLI tests and TestModel. The following are required checks for
future runtime/model changes, not measurements newly performed by this document:

- Check default/parameter rejection, N1 and all-N boundaries with matching
  configuration; canonical score ties and member sums across batch boundaries.
- Preserve legacy singleton vectors/predictions and explicit atomic member
  aggregation; keep singleton-training limitations specific to these CBMs.
- Preserve atomic pod+regular-shard constraints, masks/pins, GPU/NUMA/disks and
  removals/recreates through the common engine. Shortlist selection is read-only.
- Reject invalid models/names/types/rows/predictions; check native close and
  constructor-failure cleanup. Compare actual loaded artifact hashes.
- For retraining, split source clouds before fitting/selection, export a labelled
  provenance manifest and establish Java/trainer score parity with a declared
  numerical tolerance. Existing runtime tests do not supply missing training data.
- Compare full current traces with FitCount/Random20 and fixed model artifacts,
  B1/B100 separately, accepted logical vectors/refusals and full operation latency.
  Source hashing/loading/initialization are reported separately; feature building,
  native conversion/inference, ranking, validation and commit are timed.
