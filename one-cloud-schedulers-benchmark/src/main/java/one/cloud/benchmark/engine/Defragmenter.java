package one.cloud.benchmark.engine;

import java.util.Map;
import java.util.Optional;

import one.cloud.benchmark.model.Request;

/** Repairs the current placement using a bounded search on the engine's owner thread. */
@FunctionalInterface
public interface Defragmenter extends AutoCloseable {
    /**
     * Searches for valid relocations in the current placement.
     * A present target is retried inside its scheduling window and may be admitted together with the moves.
     * An empty target allows proactive moves to make room for future requests.
     * If the search finds no valid plan, existing placements stay unchanged.
     *
     * @param target the pending request to admit, or empty for proactive defragmentation
     * @throws Exception if the search or application of a plan fails
     */
    void defragment(Optional<Request> target) throws Exception;

    /** Returns the cumulative number of candidate planner calls, or zero if this count is not reported. */
    default long plannerCalls() {
        return 0;
    }

    /** Returns runtime settings and backend information, or an empty map if not reported. */
    default Map<String, String> parameters() {
        return Map.of();
    }

    /** Returns diagnostic counters for the current run, or an empty map if not reported. */
    default Map<String, Long> counters() {
        return Map.of();
    }

    /**
     * Releases resources owned by the defragmenter. The default implementation does nothing.
     *
     * @throws Exception if cleanup fails
     */
    @Override
    default void close() throws Exception {
    }
}
