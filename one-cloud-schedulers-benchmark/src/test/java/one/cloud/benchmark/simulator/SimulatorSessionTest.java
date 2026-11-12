package one.cloud.benchmark.simulator;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.runner.DatasetRunner;
import one.cloud.benchmark.runner.Scenario;
import one.cloud.benchmark.testsupport.TestModel;

import static one.cloud.benchmark.testsupport.TestModel.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulatorSessionTest {
    @Test
    void stepsThroughCreateRejectAndDeleteAndReset() throws Exception {
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("fitcount")))) {
            session.step(2);
            final var counters = counters(session);
            assertEquals(1, counters.get("placedUnits").getAsInt());
            assertEquals(1, counters.get("rejectedUnits").getAsInt());
            session.step(2);
            assertEquals(0, counters(session).get("activeUnits").getAsInt());
            assertEquals(1, counters(session).get("skippedRemovals").getAsInt());
            session.reset();
            assertEquals(0, session.cursor());
            assertEquals(0, counters(session).get("attemptedUnits").getAsInt());
        }
    }

    @Test
    void reactiveIlpUpdatesHostMembersAfterRelocationsAndDeletes() throws Exception {
        final var scenario = Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1, 2}, 8, 8), alloc(2, new int[]{1, 2}, 7, 10),
                alloc(3, new int[]{1, 2}, 2, 3), alloc(4, new int[]{1, 2}, 8, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 3, 2), resource(5, 4, 2)},
                3, 4, 5, -5, -4, -3));
        final var settings = new AlgorithmRegistry.Settings("reactive-ilp", Map.of("timeoutMs", new JsonPrimitive(5000)));
        try (var session = new SimulatorSession(ScenarioInput.from(scenario), List.of(settings))) {
            session.step(2);
            assertEquals(2, session.inspect(0, 1).get("activeResources").getAsInt());
            assertEquals(0, session.inspect(0, 2).get("activeResources").getAsInt());
            session.step(1);
            assertEquals(3, counters(session).get("placedUnits").getAsInt());
            assertArrayEquals(new int[]{5}, memberIds(session.inspect(0, 1)));
            assertArrayEquals(new int[]{3, 4}, memberIds(session.inspect(0, 2)));
            session.step(3);
            assertEquals(3, counters(session).get("removedUnits").getAsInt());
            assertEquals(0, counters(session).get("usedHosts").getAsInt());
        }
    }

    @Test
    void addingAlgorithmReplaysTheSamePrefix() throws Exception {
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("fitcount")))) {
            session.step(2);
            session.setAlgorithms(List.of(AlgorithmRegistry.defaults("fitcount"),
                    AlgorithmRegistry.defaults("geometric-best-fit")));
            assertEquals(2, session.cursor());
            final var lanes = session.state(0, 10, 0, 10).getAsJsonArray("lanes");
            assertEquals(2, lanes.size());
            assertEquals(lanes.get(0).getAsJsonObject().get("counters"),
                    lanes.get(1).getAsJsonObject().get("counters"));
            assertEquals(1, session.inspect(0, 2).getAsJsonObject("assignment").get("host").getAsInt());
        }
    }

    @Test
    void reportsActualCapacitiesAndDoesNotAllowInvalidSteps() throws Exception {
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("fitcount")))) {
            assertThrows(IllegalArgumentException.class, () -> session.step(-1));
            session.step(1);
            final var resources = session.inspect(0, 1).getAsJsonArray("resources");
            final var cpu = resources.get(0).getAsJsonObject();
            assertEquals(4, cpu.get("total").getAsDouble());
            assertEquals(0, cpu.get("free").getAsDouble());
            assertEquals(20, resources.size());
        }
    }

    @Test
    void invalidAlgorithmSelectionPreservesExistingState() throws Exception {
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("fitcount")))) {
            session.step(1);
            assertThrows(IllegalArgumentException.class, () -> session.setAlgorithms(List.of(
                    new AlgorithmRegistry.Settings("missing", Map.of()))));
            assertEquals(1, session.cursor());
            assertEquals(1, counters(session).get("activeUnits").getAsInt());
            session.step(3);
            assertEquals(0, counters(session).get("activeUnits").getAsInt());
        }
    }

    @Test
    void reportsEffectiveAlgorithmParametersAndRejectsUnsupportedOnesBeforeReplay() throws Exception {
        final var selected = new AlgorithmRegistry.Settings("fitcount",
                Map.of("fitCap", new JsonPrimitive(3)));
        try (var session = new SimulatorSession(input(), List.of(selected))) {
            session.step(1);
            final var effective = session.state(0, 10, 0, 0).getAsJsonArray("lanes").get(0)
                    .getAsJsonObject().getAsJsonObject("algorithm");
            assertEquals(3, effective.getAsJsonObject("parameters").get("fitCap").getAsInt());
            assertEquals(44, effective.getAsJsonObject("parameters").get("seed").getAsLong());
            assertEquals(64, effective.getAsJsonObject("parameters").get("cacheRows").getAsInt());
            assertFalse(effective.has("seed"));
            assertThrows(IllegalArgumentException.class, () -> session.setAlgorithms(List.of(
                    new AlgorithmRegistry.Settings("fitcount",
                            Map.of("topN", new JsonPrimitive(5))))));
            assertEquals(1, session.cursor());
            assertEquals(1, counters(session).get("activeUnits").getAsInt());
            assertEquals(3, session.settings().get(0).parameters().get("fitCap").getAsInt());
        }
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("catboost-top1")))) {
            final var algorithm = session.state(0, 10, 0, 0).getAsJsonArray("lanes").get(0)
                    .getAsJsonObject().getAsJsonObject("algorithm");
            assertTrue(algorithm.getAsJsonObject("parameters").isEmpty());
            session.step(1);
            assertEquals(1, counters(session).get("placedUnits").getAsInt());
            final var probe = session.probes().getAsJsonArray("lanes").get(0).getAsJsonObject();
            assertEquals(10, probe.get("perHostCap").getAsInt());
        }
    }

    @Test
    void manualExampleKeepsAtomicMembersAndGpuSharesConsistent() throws Exception {
        final var input = ManualScenario.compile(ManualScenario.example());
        try (var session = new SimulatorSession(input, List.of(AlgorithmRegistry.defaults("fitcount")))) {
            for (int index = 0; index < input.events().length; index++) {
                session.step(1);
                final var state = session.state(0, 48, 0, 0);
                assertFalse(state.get("failed").getAsBoolean());
                final var resources = state.getAsJsonArray("lanes").get(0).getAsJsonObject()
                        .getAsJsonArray("resources");
                for (final var value : resources) {
                    final var resource = value.getAsJsonObject();
                    assertTrue(resource.get("free").getAsDouble() >= -1e-8);
                    assertTrue(resource.get("used").getAsDouble() >= -1e-8);
                }
            }
        }
    }

    @Test
    void everyRegisteredAlgorithmCanReplayTheDemonstration() throws Exception {
        final var input = ManualScenario.compile(ManualScenario.example());
        for (final var descriptor : AlgorithmRegistry.algorithms()) {
            if (descriptor.requiresPrior()) {
                continue;
            }
            try (var session = new SimulatorSession(input, List.of(AlgorithmRegistry.defaults(descriptor.id())))) {
                session.step(input.events().length);
                assertEquals(input.events().length, session.cursor(), descriptor.id());
                final var snapshot = session.state(0, 48, 0, 0);
                assertFalse(snapshot.get("failed").getAsBoolean(), descriptor.id());
            }
        }
    }

    @Test
    void selectingAnAlgorithmThatNeedsAPriorKeepsTheCurrentSessionIntact() throws Exception {
        try (var session = new SimulatorSession(input(), List.of(AlgorithmRegistry.defaults("fitcount")))) {
            session.step(1);
            final var before = counters(session);
            assertThrows(IllegalArgumentException.class, () -> session.setAlgorithms(
                    List.of(AlgorithmRegistry.defaults("rollout"))));
            assertEquals(1, session.cursor());
            assertEquals(before, counters(session));
            session.step(3);
            assertEquals(0, counters(session).get("activeUnits").getAsInt());
        }
    }

    @Test
    void nativeScenarioMatchesDatasetRunnerAtEveryPrefixIncludingAtomicGpuStorageAndChurn() throws Exception {
        final var scenario = nativeScenario();
        final var input = ScenarioInput.from(scenario);
        for (final var id : List.of("fitcount", "randomn-fitcount", "catboost-top1")) {
            final var settings = AlgorithmRegistry.defaults(id);
            try (var session = new SimulatorSession(input, List.of(settings))) {
                for (int prefix = 1; prefix <= input.events().length; prefix++) {
                    session.step(1);
                    final SchedulingContext[] observed = new SchedulingContext[1];
                    final var expected = DatasetRunner.run(scenario,
                            new DatasetRunner.Options(settings.parameters().containsKey("seed")
                                    ? settings.parameters().get("seed").getAsLong() : 44, prefix, 32, 10),
                            (context, seed, catalogue) -> {
                                observed[0] = context;
                                return AlgorithmRegistry.create(settings, context, catalogue);
                            });
                    final var actual = counters(session);
                    assertEquals(expected.placedUnits(), actual.get("placedUnits").getAsLong(), id);
                    assertEquals(expected.attemptedResources(), actual.get("attemptedPods").getAsLong()
                            + actual.get("attemptedShards").getAsLong(), id);
                    assertEquals(expected.placedResources(), actual.get("placedPods").getAsLong()
                            + actual.get("placedShards").getAsLong(), id);
                    assertEquals(expected.removedUnits(), actual.get("removedUnits").getAsLong(), id);
                    assertEquals(expected.skippedRemovals(), actual.get("skippedRemovals").getAsLong(), id);
                    assertEquals(expected.scheduling().count(), actual.get("attemptedUnits").getAsLong(), id);
                    for (final var request : input.requests()) {
                        for (int member = 0; member < request.size(); member++) {
                            final int resource = request.resource(member);
                            final var expectedPlan = observed[0].placement(resource);
                            final var actualPlan = session.inspect(0, resource).getAsJsonObject("assignment");
                            if (expectedPlan == null) {
                                assertNull(actualPlan, id + "/" + prefix + "/" + resource);
                            } else {
                                assertNotNull(actualPlan, id + "/" + prefix + "/" + resource);
                                assertEquals(expectedPlan.host(), actualPlan.get("host").getAsInt());
                                assertEquals(expectedPlan.numa(), actualPlan.get("numa").getAsInt());
                                assertArrayEquals(expectedPlan.devices(), integers(actualPlan, "devices"));
                                assertArrayEquals(expectedPlan.diskOffsets(), integers(actualPlan, "diskOffsets"));
                                assertArrayEquals(expectedPlan.gpus(), integers(actualPlan, "gpus"));
                            }
                        }
                    }
                }
                assertTrue(counters(session).get("rejectedUnits").getAsLong() > 0);
                assertTrue(counters(session).get("placedGpuPods").getAsLong() > 0);
                assertTrue(counters(session).get("placedShards").getAsLong() > 0);
            }
        }
    }

    @Test
    void appendingManualRequestAndReplayingFrozenCataloguePreservesPastDecisions() throws Exception {
        final var original = ManualScenario.example();
        final var initial = ManualScenario.compile(original);
        assertEquals(original.requests().size(), initial.catalogue().length);
        final var frozenCatalogue = original.requests().stream().map(ManualScenario.Unit::name).toList();
        final var units = new java.util.ArrayList<>(original.requests());
        units.add(new ManualScenario.Unit("new-unit", List.of(new ManualScenario.Member("new-pod", "POD",
                Map.of("VCORES", 12.0, "MEM_MIB", 16384.0, "NVIDIA_GPUS", 1.0), null, null, null))));
        final var events = new java.util.ArrayList<>(original.events().subList(0, 12));
        events.add(new ManualScenario.Event("create", "new-unit"));
        final var appended = ManualScenario.compile(new ManualScenario.Config(original.hosts(), units,
                events, frozenCatalogue));
        final var settings = List.of(AlgorithmRegistry.defaults("fitcount"));
        try (var before = new SimulatorSession(initial, settings);
                var after = new SimulatorSession(appended, settings)) {
            before.step(12);
            after.step(12);
            assertEquals(counters(before), counters(after));
            for (final var request : initial.requests()) {
                for (int member = 0; member < request.size(); member++) {
                    final int resource = request.resource(member);
                    assertEquals(before.inspect(0, resource), after.inspect(0, resource));
                }
            }
            assertEquals(before.state(0, 48, 0, 0).getAsJsonArray("catalogue"),
                    after.state(0, 48, 0, 0).getAsJsonArray("catalogue"));
            after.step(1);
            assertEquals(13, after.cursor());
        }
    }

    private static int[] memberIds(JsonObject host) {
        return host.getAsJsonArray("members").asList().stream()
                .mapToInt(member -> member.getAsJsonObject().get("id").getAsInt()).sorted().toArray();
    }

    private static int[] integers(JsonObject value, String field) {
        final var values = value.getAsJsonArray(field);
        final var result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index).getAsInt();
        }
        return result;
    }

    private static Scenario nativeScenario() throws Exception {
        final var plan = DiskPlan.newBuilder().setMedia(Media.SSD).setExtents(10)
                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1);
        return Scenario.from(trace(new Record[]{
                alloc(1, new int[]{1, 2, 11}, 2, 4096, 1),
                alloc(2, new int[]{18, 19}, 50, 1),
                alloc(3, new int[]{1, 2, 11}, 1, 1024, .5),
                alloc(4, new int[0]), alloc(5, new int[]{1}, 3), alloc(6, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.SSD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.DISK).setParent(2).setMedia(Media.SSD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setRelation(Relation.COLOCATE).setDomain(1).setTarget(6))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).addDisks(plan)),
                rules(Constraints.newBuilder().setId(6).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3),
                resource(5, 3, 4), resource(6, 4, 5), resource(7, 5, 6), resource(8, 3, 6), resource(9, 6, 6)},
                5, 6, 7, 8, 9, -8, 8, -9, -8, -7, -6, -5));
    }

    @Test
    void unboundedDeviceIopsSerializesAsUnknownCapacityInsteadOfInfinity() throws Exception {
        final var model = TestModel.model(
                alloc(1, new int[]{1, 2}, 4, 8192),
                alloc(2, new int[]{18, 19, 20}, 100, 1, Double.MAX_VALUE),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.SSD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2));
        final var input = new ScenarioInput(model, new Request[0], new int[0], Map.of(),
                new String[0], new Request[0], "test", "test", 0, 0, 0);
        try (var session = new SimulatorSession(input, List.of(AlgorithmRegistry.defaults("fitcount")))) {
            final var state = session.state(0, 48, 0, 0);
            final var json = new com.google.gson.GsonBuilder().serializeNulls()
                    .setStrictness(com.google.gson.Strictness.STRICT).create();
            assertDoesNotThrow(() -> json.toJson(state));
            final var lane = state.getAsJsonArray("lanes").get(0).getAsJsonObject();
            for (final var view : List.of(lane, session.inspect(0, 1), session.inspect(0, 2))) {
                final var iops = view.getAsJsonArray("resources").get(19).getAsJsonObject();
                assertTrue(iops.get("total").isJsonNull());
                assertTrue(iops.get("free").isJsonNull());
                assertTrue(iops.get("used").isJsonNull());
                assertFalse(iops.get("capacityKnown").getAsBoolean());
                assertEquals("unbounded-in-model", iops.get("reason").getAsString());
                assertDoesNotThrow(() -> json.toJson(view));
            }
        }
    }

    private static com.google.gson.JsonObject counters(SimulatorSession session) {
        return session.state(0, 10, 0, 10).getAsJsonArray("lanes").get(0)
                .getAsJsonObject().getAsJsonObject("counters");
    }

    private static ScenarioInput input() throws Exception {
        final var model = TestModel.model(
                alloc(1, new int[]{1, 2}, 4, 8192),
                alloc(2, new int[]{1, 2}, 4, 1024),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2));
        final var requests = new Request[]{new Request(2), new Request(3)};
        return new ScenarioInput(model, requests, new int[]{1, 2, -1, -2},
                Map.of(1, "host", 2, "first", 3, "second"), new String[]{"first", "second"},
                requests, "test", "test", 4, 0, 0);
    }
}
