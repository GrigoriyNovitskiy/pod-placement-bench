package one.cloud.schedulers;

import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.DEVICE_SPINDLES_VALUE;

/**
 * The common engine owns validation, occupancy, release and counters. Algorithms own their indexes
 * and caches. State changes and algorithm notifications are synchronous and confined to one owner
 * thread. Notifications may read state but must not reenter state-changing operations.
 * Model arrays and committed placements are borrowed, read-only views.
 *
 * Requests in a schedule window are disjoint, with a declared dependency-valid order. Only those
 * exact units may be committed, at most once and in that order; an atomic unit need not share one
 * host unless its constraints require colocation.
 */
public interface PlacementContext {
    PlacementModel model();

    /** Advances once per committed unit or removal, including changes to group/domain counters. */
    long revision();

    /** Advances when a host's physical budget changes, including its NUMA nodes and devices. */
    long capacityRevision(int host);

    /** Remaining host, NUMA or device capacity in the model's allocation units. */
    double available(int inventoryResource, int dimension);

    /** Media-specific view of a physical device; extents and IOPS are shared between its views. */
    default double available(int inventoryResource, int dimension, int media) {
        if (media != 0 && dimension == DEVICE_SPINDLES_VALUE
                && media != model().rules.media[model().constraints[inventoryResource]]) {
            throw new UnsupportedOperationException("Alternative spindle capacity is unavailable");
        }
        return available(inventoryResource, dimension);
    }

    /** The committed assignment, or null when absent. Arrays are engine-owned and read-only. */
    Placement placement(int resource);

    int groupCount(int group);

    int domainCount(int group, int domainLevel, int domainValue);

    /**
     * Checks exactly one assignment per member against the combined tentative state: scalar, NUMA
     * and device budgets and all intra-unit links and group/domain constraints. Malformed inputs
     * throw; a well-formed but infeasible assignment returns false. This call changes no canonical
     * state or revisions, retains no borrowed buffers and invokes no algorithm notifications.
     */
    boolean canPlace(PlacementRequest request, Placement... plans);

    /**
     * Validates the entire announced unit and snapshots all submitted buffers before any mutation.
     * An invalid, infeasible, repeated, out-of-window or out-of-order commit throws. Validation
     * failure leaves state unchanged. A successful commit updates the entire unit and revisions
     * before notifying the algorithm in request member order. Notification failure aborts the run;
     * arbitrary callback effects are not rolled back. Submission buffers may be reused on return.
     */
    void place(PlacementRequest request, Placement... plans);
}
