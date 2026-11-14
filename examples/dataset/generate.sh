#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
    exit 1
fi

script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
project_dir=$(CDPATH= cd -P "$script_dir/../.." && pwd)
runtime_file=$(mktemp)
trap 'rm -f "$runtime_file"' 0

"$project_dir/gradlew" --quiet -p "$project_dir" \
    :one-cloud-schedulers-benchmark:writePlacementDatasetRuntime \
    "-PdatasetRuntimeFile=$runtime_file"

java_executable=$(sed -n '1p' "$runtime_file")
runtime_classpath=$(sed -n '2p' "$runtime_file")
"$java_executable" --class-path "$runtime_classpath" "$script_dir/MakeDataset.java" "$1"
