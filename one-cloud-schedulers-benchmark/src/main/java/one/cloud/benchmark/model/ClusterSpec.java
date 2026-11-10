package one.cloud.benchmark.model;

import java.util.Objects;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.Dataset.Allocs;
import one.cloud.benchmark.dataset.Dataset.CandidateSets;
import one.cloud.benchmark.dataset.Dataset.Rules;

/** Shared static tables. Treat their arrays as read-only. */
public final class ClusterSpec {
    public enum GpuMode {
        AGGREGATE, DEVICES_20
    }

    public final long id;
    public final int hostCount;
    public final int dimensionCount;
    public final long extentBytes;
    public final int[] alloc;
    public final int[] constraints;
    public final Allocs allocations;
    public final Rules rules;
    public final CandidateSets candidates;
    public final GpuMode gpuMode;
    public final GpuTopology gpus;

    private ClusterSpec(Dataset dataset, GpuMode gpuMode) {
        Objects.requireNonNull(dataset);
        this.gpuMode = Objects.requireNonNull(gpuMode);
        id = dataset.id;
        hostCount = dataset.hostCount;
        dimensionCount = dataset.dimensionCount;
        extentBytes = dataset.extentBytes;
        alloc = dataset.alloc;
        constraints = dataset.constraints;
        allocations = dataset.allocations;
        rules = dataset.rules;
        candidates = dataset.candidates;
        gpus = gpuMode == GpuMode.DEVICES_20 ? new GpuTopology(this) : null;
    }

    public static ClusterSpec from(Dataset dataset) {
        return from(dataset, GpuMode.DEVICES_20);
    }

    public static ClusterSpec from(Dataset dataset, GpuMode gpuMode) {
        return new ClusterSpec(dataset, gpuMode);
    }
}
