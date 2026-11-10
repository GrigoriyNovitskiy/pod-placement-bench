package one.cloud.schedulers.model;

import one.cloud.schedulers.dataset.PlacementDataset;
import one.cloud.schedulers.dataset.PlacementDataset.Allocs;
import one.cloud.schedulers.dataset.PlacementDataset.CandidateSets;
import one.cloud.schedulers.dataset.PlacementDataset.Rules;

/** Static tables shared with the decoder. All arrays are read-only; replay state is excluded. */
public final class PlacementModel {
    public final long id;
    public final int hostCount;
    public final int dimensionCount;
    public final long extentBytes;
    public final int[] alloc;
    public final int[] constraints;
    public final Allocs allocations;
    public final Rules rules;
    public final CandidateSets candidates;

    private PlacementModel(PlacementDataset dataset) {
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

    public static PlacementModel from(PlacementDataset dataset) {
        return new PlacementModel(dataset);
    }
}
