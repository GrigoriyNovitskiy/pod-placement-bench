# pod-placement-bench
A reproducible benchmark for cloud workload placement algorithms, comparing scheduling speed, resource fragmentation, and placement success across diverse cluster states and workload patterns.

The Java 25 scheduler API, fit-count baseline and protobuf dataset decoder live in
[one-cloud-schedulers-benchmark](one-cloud-schedulers-benchmark/README.md).

Build and test from this directory:

```sh
./gradlew :one-cloud-schedulers-benchmark:build
```

Read a dataset:

```sh
./gradlew :one-cloud-schedulers-benchmark:runPlacementDatasetRead \
  --args='/path/to/snapshot.pb /path/to/operations.pb'
```

The only runtime dependency is public `protobuf-java`; no internal cloud checkout
is needed. See the module README for API usage and the current baseline scope.

Replay a dataset with the fit-count baseline:

```sh
./gradlew --quiet :one-cloud-schedulers-benchmark:run \
  --args='--dataset /path/to/sc --algorithm fitcount --max-operations 200 --catalogue-limit 32'
```

Omit the limits to replay the complete normalized workload with the full catalogue.
See the module README for JSON output, lifecycle normalization and measurement scope.

The default `devices20` model uses synthetic individual GPUs with 20 shares per
device. Select `--gpu-model aggregate` for legacy pooled GPU accounting. The device
model preserves fractional-request boundaries for every algorithm without changing
the protobuf files; its assumptions and input validation are documented in the
module README.

Use `--algorithm fgd` for the GPU fragmentation gradient descent baseline. It uses
the default device model and the declared workload catalogue; see
[FGD](one-cloud-schedulers-benchmark/FGD.md) for the scoring rule and atomic-request adaptation.
