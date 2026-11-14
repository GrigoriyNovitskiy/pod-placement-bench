package one.cloud.benchmark.engine;

import java.util.Map;
import java.util.Optional;

import one.cloud.benchmark.model.Request;

/** Repairs the current placement using a bounded search on the engine's owner thread. */
@FunctionalInterface
public interface Defragmenter extends AutoCloseable {
    /**
     * With a target, runs inside its scheduling window and may admit it together with validated moves.
     * Without a target, may move existing requests to improve future capacity.
     */
    void defragment(Optional<Request> target) throws Exception;

    default long plannerCalls() {
        return 0;
    }

    default Map<String, String> parameters() {
        return Map.of();
    }

    default Map<String, Long> counters() {
        return Map.of();
    }

    @Override
    default void close() throws Exception {
    }
}
