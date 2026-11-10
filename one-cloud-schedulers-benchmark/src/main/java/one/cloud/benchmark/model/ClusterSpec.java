package one.cloud.benchmark.model;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.Dataset.Allocs;
import one.cloud.benchmark.dataset.Dataset.CandidateSets;
import one.cloud.benchmark.dataset.Dataset.Rules;

/** Shared static tables. Treat their arrays as read-only. */
public final class ClusterSpec {
    public final long id;
    public final int hostCount;
    public final int dimensionCount;
    public final long extentBytes;
    public final int[] alloc;
    public final int[] constraints;
    public final Allocs allocations;
    public final Rules rules;
    public final CandidateSets candidates;

    private ClusterSpec(Dataset dataset) {
        id = dataset.id;
        hostCount = dataset.hostCount;
        dimensionCount = dataset.dimensionCount;
        extentBytes = dataset.extentBytes;
        alloc = dataset.alloc;
        constraints = dataset.constraints;
        allocations = dataset.allocations;
        rules = dataset.rules;
        candidates = dataset.candidates;
    }

    public static ClusterSpec from(Dataset dataset) {
        return new ClusterSpec(dataset);
    }
}
