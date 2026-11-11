package one.cloud.benchmark.runner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.algorithms.fgd.Fgd;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.trace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetRunnerTest {
    @Test
    void measuresWholeAnnouncedWindowsAndReportsUnitsLogicalWorkAndPrefixCuts() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2)},
                2, 3, 4, -4, -3, -2)).withServiceBursts(2);
        final var windows = new ArrayList<Integer>();
        final DatasetRunner.Factory factory = (context, seed, catalogue) -> new SchedulingAlgorithm(context, seed) {
            final FitCount fallback = new FitCount(context, seed, catalogue);

            @Override
            public void schedule(Request[] requests) {
                windows.add(requests.length);
                for (final var request : requests) {
                    fallback.schedule(request);
                }
            }

            @Override
            public void schedule(Request request) {
                throw new AssertionError("Runner must announce the window");
            }

            @Override
            public void placed(Assignment placement) {
                fallback.placed(placement);
            }

            @Override
            public void removed(Assignment placement) {
                fallback.removed(placement);
            }

            @Override
            public Map<String, String> parameters() {
                return Map.of("policy", "window-test");
            }

            @Override
            public Map<String, Long> counters() {
                return Map.of("windows", (long) windows.size());
            }
        };
        final var full = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), factory);
        assertEquals(List.of(2, 1), windows);
        assertEquals(3, full.attemptedUnits());
        assertEquals(2, full.scheduling().count());
        assertEquals(2, full.placedUnits());
        assertEquals(2, full.removedUnits());
        assertEquals(1, full.skippedRemovals());
        assertEquals(3, full.requestedLogical()[1]);
        assertEquals(2, full.admittedLogical()[1]);
        assertEquals(1, full.refusedResourcesByAllocation()[2]);
        assertEquals("window-test", full.parameters().get("policy"));
        assertEquals(2L, full.counters().get("windows"));
        assertFalse(full.truncatedWindow());
        windows.clear();
        final var prefix = DatasetRunner.run(scenario, new DatasetRunner.Options(42, 1, 0, 10), factory);
        assertEquals(List.of(1), windows);
        assertTrue(prefix.truncatedWindow());
        assertEquals(1, prefix.attemptedUnits());
        assertEquals(1, prefix.scheduling().count());
        assertEquals(full.scenarioDigest(), prefix.scenarioDigest());
        assertEquals(full.catalogueChecksum(), prefix.catalogueChecksum());
    }

    @Test
    void fgdReplayIsRepeatableAcrossGpuChurnAndFullDrain() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1, 11}, 10, 2),
                alloc(2, new int[]{1, 11}, 1, .3), alloc(3, new int[]{1, 11}, 1, .5),
                alloc(4, new int[]{1, 11}, 1, .7),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 4, 2)},
                2, 3, 4, -3, 3, -4, -3, -2));
        final var options = new DatasetRunner.Options(17, 0, 0, 10);
        final var first = DatasetRunner.run(scenario, options, Fgd::new);
        final var second = DatasetRunner.run(scenario, options, Fgd::new);
        assertEquals(first.checksum(), second.checksum());
        assertEquals(first.catalogueChecksum(), second.catalogueChecksum());
        assertEquals(8, first.processedOperations());
        assertEquals(4, first.placedUnits());
        assertEquals(4, first.removedUnits());
        assertEquals(0, first.skippedRemovals());
        assertEquals(first.checkpoints().size(), second.checkpoints().size());
        for (int index = 0; index < first.checkpoints().size(); index++) {
            assertArrayEquals(first.checkpoints().get(index).fitCounts(), second.checkpoints().get(index).fitCounts());
        }
        assertTrue(first.plannerCalls() > 0);
    }

    @Test
    void countsDeclinesAndDeleteNoopsWithoutProbingTheEmptyFinalDrain() throws Exception {
        final var scenario = Scenario.from(trace(scalar(2, 2, 2), 2, 3, -3, -2));
        final var result = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), FitCount::new);
        assertEquals(4, result.processedOperations());
        assertEquals(2, result.scheduling().count());
        assertEquals(1, result.placedUnits());
        assertEquals(2, result.attemptedResources());
        assertEquals(1, result.placedResources());
        assertEquals(1, result.removedUnits());
        assertEquals(1, result.skippedRemovals());
        assertEquals(1, result.removal().count());
        assertEquals(1, result.peakActiveUnits());
        assertEquals(1, result.peakUsedHosts());
        assertEquals(1, result.checkpoints().size());
        final var checkpoint = result.checkpoints().getFirst();
        assertEquals(2, checkpoint.operation());
        assertEquals(2, checkpoint.logicalUnits());
        assertEquals(1, checkpoint.activeUnits());
        assertArrayEquals(new long[]{0, 0}, checkpoint.fitCounts());
        assertTrue(result.scheduling().p50Nanos() <= result.scheduling().p95Nanos());
        assertEquals(result.scheduling().maxNanos(), result.scheduling().p99Nanos());
        assertTrue(result.initializationNanos() >= result.algorithmInitializationNanos());
        assertTrue(result.replayNanos() >= result.qualityNanos());
        assertTrue(result.plannerCalls() > 0);
    }

    @Test
    void prefixKeepsFullWorkloadCatalogueAndSamplingIndependentOfTheLimit() throws Exception {
        final var scenario = Scenario.from(trace(scalar(10, 20, 1), events(10)));
        final var captured = new ArrayList<int[]>();
        final DatasetRunner.Factory factory = (context, seed, catalogue) -> {
            assertEquals(0, context.revision());
            assertEquals(20, context.available(1, 1));
            captured.add(java.util.Arrays.stream(catalogue).mapToInt(request -> request.resource(0)).toArray());
            final var first = catalogue[0];
            catalogue[0] = catalogue[catalogue.length - 1];
            catalogue[catalogue.length - 1] = first;
            return new FitCount(context, seed, catalogue);
        };
        final var options = new DatasetRunner.Options(7, 3, 3, 2);
        final var first = DatasetRunner.run(scenario, options, factory);
        final var second = DatasetRunner.run(scenario, options, factory);
        assertArrayEquals(new int[]{2, 5, 8}, captured.getFirst());
        assertArrayEquals(captured.getFirst(), captured.getLast());
        assertEquals(first.checksum(), second.checksum());
        assertEquals(first.catalogueChecksum(), second.catalogueChecksum());
        assertEquals(first.catalogueChecksum(), first.probeChecksum());
        assertEquals(first.probeChecksum(), second.probeChecksum());
        assertEquals(3, first.processedOperations());
        assertEquals(3, first.scheduling().count());
        assertEquals(0, first.removal().count());
        assertEquals(1, first.checkpoints().size());
        assertEquals(3, first.checkpoints().getFirst().operation());
        assertEquals(3, first.checkpoints().getFirst().logicalUnits());
        assertEquals(3, first.checkpoints().getFirst().activeUnits());
        assertArrayEquals(new long[]{2, 2, 2}, first.checkpoints().getFirst().fitCounts());
        final var full = DatasetRunner.run(scenario, new DatasetRunner.Options(7, 0, 3, 2), factory);
        assertEquals(first.catalogueChecksum(), full.catalogueChecksum());
        assertEquals(20, full.processedOperations());
        assertEquals(1, full.checkpoints().size());
        assertEquals(10, full.checkpoints().getFirst().operation());
    }

    @Test
    void historyChecksumIncludesGeometryEvenWhenBothRunsFinishEmpty() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2)}, 3, -3));
        final var first = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), forcedHost(1));
        final var second = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), forcedHost(2));
        assertNotEquals(first.checksum(), second.checksum());
        assertEquals(1, first.removedUnits());
        assertEquals(1, second.removedUnits());
        assertEquals(-1, first.plannerCalls());
    }

    @Test
    void historyChecksumIncludesGpuChoiceEvenWhenBothRunsFinishEmpty() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2)}, 2, -2), ClusterSpec.GpuMode.DEVICES_20);
        final DatasetRunner.Factory firstGpu = (context, seed, catalogue) -> new SchedulingAlgorithm(context, seed) {
            @Override
            public void schedule(Request request) {
                context.place(request, new Assignment(request.resource(0), 1, 0, new int[]{0}, new int[0], new int[]{1}));
            }
        };
        final DatasetRunner.Factory secondGpu = (context, seed, catalogue) -> new SchedulingAlgorithm(context, seed) {
            @Override
            public void schedule(Request request) {
                context.place(request, new Assignment(request.resource(0), 1, 0, new int[]{0}, new int[0], new int[]{2}));
            }
        };
        final var first = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), firstGpu);
        final var second = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), secondGpu);
        assertNotEquals(first.checksum(), second.checksum());
        assertEquals(first.checksum(), DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), firstGpu).checksum());
        assertEquals(1, first.placedUnits());
        assertEquals(1, first.removedUnits());
        assertEquals(1, second.removedUnits());
    }

    @Test
    void preservesFailedDependencyAdmissionInsteadOfChangingTheScenario() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 3), alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setTarget(1).setRelation(Relation.READY_GROUP).setDomain(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3)}, 2, 3, -3, -2));
        final var result = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), FitCount::new);
        assertEquals(2, result.scheduling().count());
        assertEquals(0, result.placedUnits());
        assertEquals(2, result.skippedRemovals());
        assertEquals(0, result.removal().count());
        assertEquals(2, result.checkpoints().getFirst().logicalUnits());
        assertEquals(0, result.checkpoints().getFirst().activeUnits());
    }

    @Test
    void closesFailedBackendsAndDoesNotTurnTheirFailureIntoDeclines() throws Exception {
        final var scenario = Scenario.from(trace(scalar(1, 2, 1), 2, -2));
        final var closed = new AtomicBoolean();
        final var failure = assertThrows(IOException.class, () -> DatasetRunner.run(scenario,
                DatasetRunner.Options.defaults(), (context, seed, catalogue) -> new SchedulingAlgorithm(context, seed) {
                    @Override
                    public void schedule(Request request) throws IOException {
                        throw new IOException("backend failed");
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                }));
        assertEquals("backend failed", failure.getMessage());
        assertTrue(closed.get());
    }

    @Test
    void emptyTraceAndInvalidOptionsHaveExplicitResults() throws Exception {
        final var scenario = Scenario.from(trace(scalar(0, 2, 1)));
        final var result = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), FitCount::new);
        assertEquals(0, result.processedOperations());
        assertEquals(0, result.scheduling().count());
        assertEquals(0, result.removal().count());
        assertEquals(0, result.catalogueSize());
        assertTrue(result.checkpoints().isEmpty());
        final var legacy = new DatasetRunner.Result(result.options(), result.scenarioDigest(), result.algorithmClass(),
                result.processedOperations(), result.catalogueSize(), result.catalogueChecksum(), result.probeCount(),
                result.probeChecksum(), result.initializationNanos(), result.algorithmInitializationNanos(),
                result.replayNanos(), result.qualityNanos(), result.placedUnits(), result.attemptedResources(),
                result.placedResources(), result.removedUnits(), result.skippedRemovals(), result.peakActiveUnits(),
                result.peakActiveResources(), result.peakUsedHosts(), result.plannerCalls(), result.checksum(),
                result.heapBeforeBytes(), result.sampledPeakHeapBytes(), result.scheduling(), result.removal(),
                result.checkpoints());
        assertEquals(result.checksum(), legacy.checksum());
        assertEquals(result.scheduling().count(), legacy.attemptedUnits());
        assertTrue(legacy.parameters().isEmpty());
        assertEquals(0, legacy.requestedLogical().length);
        assertThrows(IllegalArgumentException.class, () -> new DatasetRunner.Options(1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DatasetRunner.Options(1, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DatasetRunner.Options(1, 0, 0, 0));
    }

    private static DatasetRunner.Factory forcedHost(int host) {
        return (context, seed, catalogue) -> new SchedulingAlgorithm(context, seed) {
            @Override
            public void schedule(Request request) {
                context.place(request, new Assignment(request.resource(0), host, 0, new int[]{0}, new int[0]));
            }
        };
    }

    private static Record[] scalar(int pods, double capacity, double demand) {
        final var result = new ArrayList<Record>();
        result.add(alloc(1, new int[]{1}, capacity));
        result.add(alloc(2, new int[]{1}, demand));
        result.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        result.add(rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)));
        result.add(resource(1, 1, 1));
        for (int index = 0; index < pods; index++) {
            result.add(resource(index + 2, 2, 2));
        }
        return result.toArray(Record[]::new);
    }

    private static int[] events(int pods) {
        final int[] result = new int[pods * 2];
        for (int index = 0; index < pods; index++) {
            result[index] = index + 2;
            result[pods + index] = -(pods + 1 - index);
        }
        return result;
    }
}
