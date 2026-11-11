package one.cloud.benchmark.simulator;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.algorithms.AlgorithmDefinition;
import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.Request;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualScenarioTest {
    @Test
    void openEndedAtomicUnitsUseRealStorageAndGpuCapacityAndReleaseEverything() throws Exception {
        final var host = new ManualScenario.Host("host-1", Map.of("VCORES", 8.0, "MEM_MIB", 8192.0,
                "NVIDIA_GPUS", 1.0), List.of(new ManualScenario.Disk("ssd-1", "SSD",
                        Map.of("DEVICE_EXTENTS", 100.0, "DEVICE_SPINDLES", 1.0))));
        final var pod = new ManualScenario.Member("api", "POD", Map.of("VCORES", 2.0,
                "MEM_MIB", 1024.0, "NVIDIA_GPUS", .25), null, List.of("host-1"), null);
        final var shard = new ManualScenario.Member("volume", "SHARD", Map.of(), null, null,
                List.of(new ManualScenario.DiskPlan("SSD", "LINEAR", 20, .1, null, false)));
        final var input = ManualScenario.compile(new ManualScenario.Config(List.of(host),
                List.of(new ManualScenario.Unit("app", List.of(pod, shard))),
                List.of(new ManualScenario.Event("create", "app"), new ManualScenario.Event("delete", "app"),
                        new ManualScenario.Event("create", "app")), List.of("app")));
        assertArrayEquals(new int[]{1, -1, 1}, input.events());
        assertEquals(2, input.requests()[0].size());
        final var engine = new Engine(input.model());
        final double storage = engine.available(2, 18);
        try (final var algorithm = AlgorithmRegistry.create(AlgorithmRegistry.defaults("fitcount"),
                engine, input.catalogue())) {
            final Request request = input.requests()[0];
            engine.schedule(algorithm, request);
            assertNotNull(engine.placement(request.resource(0)));
            assertNotNull(engine.placement(request.resource(1)));
            assertEquals(1, engine.placement(request.resource(0)).host());
            assertEquals(1, engine.placement(request.resource(1)).host());
            assertEquals(15, engine.gpuAvailable(1));
            assertEquals(80, engine.available(2, 18));
            engine.remove(request);
            assertNull(engine.placement(request.resource(0)));
            assertEquals(20, engine.gpuAvailable(1));
            assertEquals(storage, engine.available(2, 18));
        }
    }

    @Test
    void appendingRequestsPreservesResourceIdsAndExplicitCatalogue() throws Exception {
        final var first = unit("first", 2);
        final var second = unit("second", 4);
        final var host = new ManualScenario.Host("host", Map.of("VCORES", 8.0), null);
        final var original = ManualScenario.compile(new ManualScenario.Config(List.of(host), List.of(first),
                null, List.of("first")));
        final var appended = ManualScenario.compile(new ManualScenario.Config(List.of(host), List.of(first, second),
                List.of(new ManualScenario.Event("create", "first")), List.of("first")));
        assertEquals(original.requests()[0].resource(0), appended.requests()[0].resource(0));
        assertEquals(1, appended.catalogue().length);
        assertEquals(original.catalogue()[0].resource(0), appended.catalogue()[0].resource(0));
        assertEquals(original.digest(), ManualScenario.compile(new ManualScenario.Config(List.of(host),
                List.of(first), null, List.of("first"))).digest());
    }

    @Test
    void rejectsInvalidLifetimesUnknownHostsAndUnsupportedGpuFractions() {
        final var host = new ManualScenario.Host("host", Map.of("VCORES", 8.0), null);
        final var request = unit("pod", 1);
        for (final var events : List.of(
                List.of(new ManualScenario.Event("delete", "pod")),
                List.of(new ManualScenario.Event("create", "pod"), new ManualScenario.Event("create", "pod")),
                List.of(new ManualScenario.Event("create", "missing")))) {
            assertThrows(IllegalArgumentException.class, () -> ManualScenario.compile(new ManualScenario.Config(
                    List.of(host), List.of(request), events, null)));
        }
        final var pinned = new ManualScenario.Unit("pod", List.of(new ManualScenario.Member("pod", "POD",
                Map.of(), "missing", null, null)));
        assertThrows(IllegalArgumentException.class, () -> ManualScenario.compile(new ManualScenario.Config(
                List.of(host), List.of(pinned), null, null)));
        final var fractional = new ManualScenario.Unit("pod", List.of(new ManualScenario.Member("pod", "POD",
                Map.of("NVIDIA_GPUS", 1.5), null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> ManualScenario.compile(new ManualScenario.Config(
                List.of(host), List.of(fractional), null, null)));
        assertThrows(IllegalArgumentException.class, () -> ManualScenario.compile(new ManualScenario.Config(
                List.of(new ManualScenario.Host("host", Map.of("DEVICE_EXTENTS", 3.0), null)),
                List.of(), null, null)));
    }

    @Test
    void exampleCompilesAndEveryBuiltinAlgorithmCanBeCreated() throws Exception {
        final var input = ManualScenario.compile(ManualScenario.example());
        assertTrue(input.events().length > 0);
        assertEquals(64, input.digest().length());
        for (final var descriptor : AlgorithmRegistry.algorithms()) {
            if (descriptor.requiresPrior()) {
                continue;
            }
            try (final var algorithm = AlgorithmRegistry.create(AlgorithmRegistry.defaults(descriptor.id()),
                    new Engine(input.model()), input.catalogue())) {
                assertNotNull(algorithm);
            }
        }
    }

    @Test
    void customAlgorithmFactoryIsAvailableToTheSimulatorWithoutChangingItsCode() throws Exception {
        final var definition = new AlgorithmDefinition("test-custom", "Custom algorithm",
                List.of(), (context, catalogue, parameters) -> new GeometricBestFit(context, 44));
        AlgorithmRegistry.register(definition);
        assertTrue(AlgorithmRegistry.algorithms().stream().anyMatch(value -> value.id().equals("test-custom")));
        final var input = ManualScenario.compile(ManualScenario.example());
        final var engine = new Engine(input.model());
        try (final var algorithm = AlgorithmRegistry.create(AlgorithmRegistry.defaults("test-custom"),
                engine, input.catalogue())) {
            engine.schedule(algorithm, input.requests()[0]);
            assertNotNull(engine.placement(input.requests()[0].resource(0)));
        }
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.register("test-custom", "Duplicate",
                List.of(), (context, catalogue, parameters) -> new GeometricBestFit(context, 44)));
    }

    @Test
    void algorithmParametersAreSpecificAndValidateTypesRangesAndUnknownKeys() {
        assertTrue(AlgorithmRegistry.defaults("catboost-top1").parameters().isEmpty());
        final var fitcount = AlgorithmRegistry.normalize(new AlgorithmRegistry.Settings("fitcount",
                Map.of("fitCap", new JsonPrimitive(3))));
        assertEquals(3, fitcount.parameters().get("fitCap").getAsInt());
        assertEquals(44, fitcount.parameters().get("seed").getAsInt());
        assertEquals(64, fitcount.parameters().get("cacheRows").getAsInt());
        assertThrows(UnsupportedOperationException.class,
                () -> fitcount.parameters().put("seed", new JsonPrimitive(8)));
        for (final var parameters : List.of(Map.of("topN", new JsonPrimitive(20)),
                Map.of("fitCap", new JsonPrimitive("3")), Map.of("fitCap", new JsonPrimitive(1.5)),
                Map.of("fitCap", new JsonPrimitive(0)), Map.of("seed", new JsonPrimitive(9007199254740992L)),
                Map.of("fitCap", new JsonPrimitive(Double.NaN)))) {
            assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.normalize(
                    new AlgorithmRegistry.Settings("fitcount", parameters)));
        }
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.normalize(
                new AlgorithmRegistry.Settings("catboost-top1", Map.of("seed", new JsonPrimitive(44)))));
    }

    @Test
    void customParametersReachTheJavaFactoryAndInvalidSchemasAreRejected() throws Exception {
        final var received = new java.util.concurrent.atomic.AtomicReference<Map<String, JsonPrimitive>>();
        final var parameters = List.of(
                AlgorithmRegistry.Parameter.integer("attempts", "Attempts", "Number of attempts.", 3, 1, 10),
                AlgorithmRegistry.Parameter.number("weight", "Weight", "Placement score weight.", .5, 0, 1),
                AlgorithmRegistry.Parameter.bool("enabled", "Enabled", "Enable the custom rule.", true),
                AlgorithmRegistry.Parameter.text("label", "Label", "User-provided label.", "default"));
        AlgorithmRegistry.register("test-parameters", "Custom parameters", parameters, (context, catalogue, values) -> {
            received.set(values);
            return new GeometricBestFit(context, values.get("attempts").getAsLong());
        });
        final var input = ManualScenario.compile(ManualScenario.example());
        try (final var algorithm = AlgorithmRegistry.create(new AlgorithmRegistry.Settings("test-parameters",
                Map.of("attempts", new JsonPrimitive(7), "label", new JsonPrimitive("custom"))),
                new Engine(input.model()), input.catalogue())) {
            assertNotNull(algorithm);
            assertEquals(7, received.get().get("attempts").getAsInt());
            assertEquals(.5, received.get().get("weight").getAsDouble());
            assertTrue(received.get().get("enabled").getAsBoolean());
            assertEquals("custom", received.get().get("label").getAsString());
        }
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.register("test-invalid-schema",
                "Duplicate keys", List.of(parameters.get(0), parameters.get(0)),
                (context, catalogue, values) -> new GeometricBestFit(context, 42)));
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.register("test-invalid-default",
                "Invalid default", List.of(AlgorithmRegistry.Parameter.integer(
                        "count", "Count", "Number of attempts.", 100, 1, 10)),
                (context, catalogue, values) -> new GeometricBestFit(context, 42)));
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.register("test-missing-schema",
                "Missing schema", null, (context, catalogue, values) -> new GeometricBestFit(context, 42)));
        assertThrows(IllegalArgumentException.class, () -> new AlgorithmDefinition("test-missing-definition-schema",
                "Missing schema", null, (context, catalogue, values) -> new GeometricBestFit(context, 42)));

        final var descriptor = AlgorithmRegistry.algorithms().stream()
                .filter(value -> value.id().equals("test-parameters")).findFirst().orElseThrow();
        assertEquals("Number of attempts.", descriptor.parameters().get(0).description());
        assertEquals("Number of attempts.", descriptor.parameters().get(0).asAdvanced().description());
        for (final String description : new String[]{null, "", "   "}) {
            final var invalid = new AlgorithmDefinition("test-no-description", "Missing description",
                    List.of(AlgorithmRegistry.Parameter.integer("count", "Count", description, 3, 1, 10)),
                    (context, catalogue, values) -> new GeometricBestFit(context, 42));
            assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.register(invalid));
        }

        for (final var algorithm : AlgorithmRegistry.algorithms()) {
            for (final var parameter : algorithm.parameters()) {
                assertNotNull(parameter.description());
                assertFalse(parameter.description().isBlank(), algorithm.id() + "/" + parameter.key());
            }
        }
    }

    @Test
    void bulkAndDpRegistrationPreservesNewDefaultsAndSchedulesTheWholeWindow() throws Exception {
        final var input = ManualScenario.compile(new ManualScenario.Config(
                List.of(new ManualScenario.Host("host", Map.of("VCORES", 1.0), null)),
                List.of(unit("first", 1), unit("second", 1)), null, null));
        for (final var id : List.of("bulk-fitcount", "dp")) {
            final var settings = AlgorithmRegistry.defaults(id);
            assertEquals(42, settings.parameters().get("seed").getAsInt());
            assertEquals(128, settings.parameters().get("fitCap").getAsInt());
            assertEquals(256, settings.parameters().get("cacheRows").getAsInt());
            final var engine = new Engine(input.model());
            try (final var algorithm = AlgorithmRegistry.create(settings, engine, input.catalogue())) {
                engine.schedule(algorithm, input.requests());
                assertEquals(1, algorithm.counters().get("bulk_decisions"));
                assertEquals("128", algorithm.parameters().get("copy_cap"));
                int placed = 0;
                for (final var request : input.requests()) {
                    placed += engine.placement(request.resource(0)) == null ? 0 : 1;
                }
                assertEquals(1, placed);
            }
        }
        final var rollout = AlgorithmRegistry.algorithms().stream()
                .filter(descriptor -> descriptor.id().equals("rollout")).findFirst().orElseThrow();
        assertTrue(rollout.requiresPrior());
        assertEquals(1, rollout.maxBulkSize());
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.create(
                AlgorithmRegistry.defaults("rollout"), new Engine(input.model()), input.catalogue()));
        assertThrows(IllegalArgumentException.class, () -> AlgorithmRegistry.create(
                AlgorithmRegistry.defaults("fitcount"), new Engine(input.model()), input.catalogue(),
                java.nio.file.Path.of("unused-prior.pb")));
    }

    private static ManualScenario.Unit unit(String name, double cpu) {
        return new ManualScenario.Unit(name, List.of(new ManualScenario.Member(name, "POD",
                Map.of("VCORES", cpu), null, null, null)));
    }
}
