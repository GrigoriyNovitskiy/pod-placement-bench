package one.cloud.benchmark.engine;

import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.DEVICE_SPINDLES_VALUE;

public interface SchedulingContext {
    ClusterSpec model();

    long revision();

    long capacityRevision(int host);

    double available(int inventoryResource, int dimension);

    default double available(int inventoryResource, int dimension, int media) {
        if (media != 0 && dimension == DEVICE_SPINDLES_VALUE
                && media != model().rules.media[model().constraints[inventoryResource]]) {
            throw new UnsupportedOperationException("Alternative spindle capacity is unavailable");
        }
        return available(inventoryResource, dimension);
    }

    Assignment placement(int resource);

    int groupCount(int group);

    int domainCount(int group, int domainLevel, int domainValue);

    boolean canPlace(Request request, Assignment... plans);

    void place(Request request, Assignment... plans);
}
