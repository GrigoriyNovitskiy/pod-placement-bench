package one.cloud.benchmark.engine;

import java.util.List;

import one.cloud.benchmark.model.Request;

/** Optional lifecycle for algorithms that can admit a request after its original scheduling window. */
public interface ReplayMaintenance {
    /** Cancels a request's pending lifetime before the replay processes its delete. */
    void cancel(Request request);

    /** Advances the replay clock outside the scheduling window and returns all newly admitted requests. */
    List<Request> advance(int operations) throws Exception;
}
