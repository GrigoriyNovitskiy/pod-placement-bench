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
