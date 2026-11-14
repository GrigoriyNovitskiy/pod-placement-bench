package one.cloud.benchmark.runner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetRunnerTest {
    @Test
    void computesNearestRankP90WithoutReplacingP95OrInventingLegacySamples() {
        final long[] samples = {20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        final var latency = DatasetRunner.latency(samples, samples.length, 210);
        assertEquals(10, latency.p50Nanos());
        assertEquals(Long.valueOf(18), latency.p90Nanos());
        assertEquals(19, latency.p95Nanos());
        assertEquals(20, latency.p99Nanos());
        assertEquals(20, latency.maxNanos());
        assertEquals(Long.valueOf(0), DatasetRunner.latency(new long[0], 0, 0).p90Nanos());
        assertNull(new DatasetRunner.Latency(20, 210, 10, 19, 20, 20).p90Nanos());
    }

    @Test
    void peakLogicalDemandTracksChurnRefusalsAndAtomicMembersPerDimension() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1, 2}, 10, 10), alloc(2, new int[]{1, 2}, 4, 1),
                alloc(3, new int[]{1, 2}, 1, 1), alloc(4, new int[]{1, 2}, 1, 7),
                alloc(5, new int[]{1, 2}, 20, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setTarget(3).setRelation(Relation.COLOCATE).setDomain(1))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 4, 4), resource(5, 5, 4)},
                2, 3, 4, 5, -3, -2, 2, 3, -5, -4, -3, -2));
        final var result = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), FitCount::new);
        assertEquals(3, result.placedUnits());
        assertEquals(5, result.placedResources());
        assertEquals(1, result.skippedRemovals());
        assertEquals(2, result.peakActiveUnits());
        assertEquals(3, result.peakActiveResources());
        assertEquals(11, result.admittedLogical()[1]);
        assertEquals(11, result.admittedLogical()[2]);
        assertEquals(6, result.peakActiveLogical()[1]);
        assertEquals(9, result.peakActiveLogical()[2]);
        final var options = new BenchmarkExperiment.Options(0, 0, 10, 0, 42);
        final var quality = BenchmarkExperiment.quality(scenario, options, FitCount::new);
        assertEquals(Map.of("VCORES", 6.0, "MEM_MIB", 9.0), quality.peakActiveLogical());
        assertEquals(quality.counts(), BenchmarkExperiment.measure(scenario, options, FitCount::new).counts());
        final var measured = DatasetRunner.run(scenario, DatasetRunner.Options.defaults(), FitCount::new, null, null);
        assertEquals(0, measured.peakActiveLogical().length);
    }

    @Test
    void benchmarkContinuesAfterDeclinesAndMeasuresTheSameReplayWithoutProbes() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1}, 100), alloc(2, new int[]{1}, 200), alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.HOST).setFlags(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 2), resource(3, 2, 3), resource(4, 3, 3)},
                3, 4, -4, -3));
        final var options = new BenchmarkExperiment.Options(0, 0, 10, 50, 42);
        final var quality = BenchmarkExperiment.quality(scenario, options, FitCount::new);
        final var measured = BenchmarkExperiment.measure(scenario, options, FitCount::new);
        assertEquals(measured.counts(), quality.counts());
        assertTrue(quality.counts().complete());
        assertEquals(4, quality.counts().processedOperations());
        assertEquals(2, measured.scheduleWindows().count());
        assertTrue(measured.eventsPerSecond() > 0);
        assertEquals(1, quality.counts().admittedUnits());
        assertEquals(1, quality.counts().declinedUnits());
        assertEquals(1, quality.counts().committedRemovals());
        assertEquals(1, quality.counts().skippedRemovals());
        assertEquals(0, quality.checkpoints().getFirst().admittedActiveUnits());
        final var checkpoint = quality.checkpoints().getLast();
        assertEquals(201.0, checkpoint.offeredActiveDemand().get("VCORES"));
        assertEquals(100.0, checkpoint.utilization().get("VCORES").capacity());
        assertEquals(99.0, checkpoint.utilization().get("VCORES").available());
        assertNull(checkpoint.utilization().get("MEM_MIB").fraction());
        assertEquals(0, checkpoint.holeFilling().getFirst().additionalCopies());
        assertEquals(10, checkpoint.holeFilling().getLast().additionalCopies());
        assertEquals(1, checkpoint.holeFilling().getLast().hostsAtCopyCap());
        final var inflation = quality.inflation();
        assertEquals(2, inflation.operation());
        assertEquals(50, inflation.attemptedUnits());
        assertEquals(50, inflation.admittedUnits() + inflation.declinedUnits());
        assertTrue(inflation.admittedAfterFirstDecline() > 0);
        assertEquals(99.0 - inflation.admittedUnits(), inflation.utilizationAfter().get("VCORES").available());
        assertEquals(quality, BenchmarkExperiment.quality(scenario, options, FitCount::new));
    }

    @Test
    void measuresWholeAnnouncedWindowsAndReportsUnitsLogicalWorkAndPrefixCuts() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2)},
                2, 3, 4, -4, -3, -2)).withServiceBursts(2);
        final var windows = new ArrayList<Integer>();
        final DatasetRunner.Factory factory = (context, seed, catalogue) -> new SchedulingAlgorithm(context) {
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
            public void capacityChanged(int host) {
                fallback.capacityChanged(host);
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
        final var report = BenchmarkExperiment.quality(scenario, new BenchmarkExperiment.Options(0, 0, 10, 0, 42),
                Fgd::new);
        final var gpus = report.checkpoints().getLast().utilization().get("NVIDIA_GPUS");
        assertEquals(2.0, gpus.capacity());
        assertEquals(1.5, gpus.usedOrUnavailable());
        assertEquals(.75, gpus.fraction());
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
    void fullWeightsReachRegistryRefinersWithoutChangingLegacySampling() throws Exception {
        final var scenario = Scenario.from(trace(scalar(10, 20, 1), events(10)));
        final var fullOptions = new DatasetRunner.Options(7, 3, 3, 2, true);
        final DatasetRunner.Factory legacyFactory = (context, seed, catalogue) -> {
            assertArrayEquals(new int[]{2, 5, 8}, java.util.Arrays.stream(catalogue)
                    .mapToInt(request -> request.resource(0)).toArray());
            final var first = catalogue[0];
            catalogue[0] = catalogue[catalogue.length - 1];
            catalogue[catalogue.length - 1] = first;
            return new FitCount(context, seed, catalogue);
        };
        final var failure = assertThrows(IllegalArgumentException.class,
                () -> DatasetRunner.run(scenario, fullOptions, legacyFactory));
        assertEquals("Full catalogue weights require a catalogue-aware factory", failure.getMessage());
        final var legacy = DatasetRunner.run(scenario, new DatasetRunner.Options(7, 3, 3, 2), legacyFactory);
        assertEquals("sampled", legacy.parameters().get("catalogue_weight_mode"));
        assertEquals("3", legacy.parameters().get("catalogue_weight_mass"));
        for (final var id : List.of("fitcount", "mean-fitcount", "randomn-fitcount", "randomn-mean-fitcount",
                "portfolio-fitcount", "portfolio-mean-fitcount", "geometric-portfolio-mean-fitcount",
                "rl-pool-fitcount", "rl-pool-mean-fitcount")) {
            final var factory = BenchmarkExperiment.factory(AlgorithmRegistry.defaults(id), null);
            final var full = DatasetRunner.run(scenario, fullOptions, factory);
            assertEquals("full", full.parameters().get("catalogue_weight_mode"), id);
            assertEquals("10", full.parameters().get("catalogue_population"), id);
            assertEquals("10", full.parameters().get("catalogue_weight_mass"), id);
            assertEquals("3", full.parameters().get("catalogue_selected"), id);
            assertEquals("1", full.parameters().get("catalogue_families"), id);
            assertEquals(legacy.catalogueChecksum(), full.catalogueChecksum(), id);
            assertEquals(legacy.probeChecksum(), full.probeChecksum(), id);
            final var complete = DatasetRunner.run(scenario, new DatasetRunner.Options(7, 0, 3, 2, true), factory);
            assertEquals(full.parameters().get("catalogue_weight_checksum"),
                    complete.parameters().get("catalogue_weight_checksum"), id);
        }
        final var options = new BenchmarkExperiment.Options(3, 3, 2, 0, 42, true);
        assertTrue(options.replayOptions().fullCatalogueWeights());
        final var quality = BenchmarkExperiment.quality(scenario, options,
                BenchmarkExperiment.factory(AlgorithmRegistry.defaults("fitcount"), null));
        assertEquals("full", quality.parameters().get("catalogue_weight_mode"));
        assertEquals("10", quality.parameters().get("catalogue_weight_mass"));
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
        final DatasetRunner.Factory firstGpu = (context, seed, catalogue) -> new SchedulingAlgorithm(context) {
            @Override
            public void schedule(Request request) {
                context.place(request, new Assignment(request.resource(0), 1, 0, new int[]{0}, new int[0], new int[]{1}));
            }
        };
        final DatasetRunner.Factory secondGpu = (context, seed, catalogue) -> new SchedulingAlgorithm(context) {
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
        final var inflation = BenchmarkExperiment.quality(scenario, new BenchmarkExperiment.Options(0, 0, 10, 3, 42),
                FitCount::new).inflation();
        assertEquals(1, inflation.eligibleTemplates());
        assertEquals(1, inflation.excludedTemplates());
        assertEquals(3, inflation.declinedUnits());
    }

    @Test
    void closesFailedBackendsAndDoesNotTurnTheirFailureIntoDeclines() throws Exception {
        final var scenario = Scenario.from(trace(scalar(1, 2, 1), 2, -2));
        final var closed = new AtomicBoolean();
        final var failure = assertThrows(IOException.class, () -> DatasetRunner.run(scenario,
                DatasetRunner.Options.defaults(), (context, seed, catalogue) -> new SchedulingAlgorithm(context) {
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
        return (context, seed, catalogue) -> new SchedulingAlgorithm(context) {
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
