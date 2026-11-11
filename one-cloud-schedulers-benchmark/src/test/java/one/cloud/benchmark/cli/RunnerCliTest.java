package one.cloud.benchmark.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.geometric.GeometricBestFit;
import one.cloud.benchmark.algorithms.catboost.CatBoostTopNFitCount;
import one.cloud.benchmark.algorithms.fitcount.RolloutPrior;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.RolloutPriorProtos;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerCliTest {
    @TempDir
    Path temporary;

    @Test
    void simulatorHelpAndInvalidArgumentsDoNotStartAServer() {
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "simulator", "--help"));
        final var help = output.toString(StandardCharsets.UTF_8);
        assertTrue(help.startsWith("Usage: placement-bench simulator"));
        assertTrue(help.contains("--port"));
        assertTrue(help.contains("--no-open"));
        assertFalse(help.contains("Placement simulator:"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        final String[][] invalid = {
                {"--unknown"}, {"--port"}, {"--port", "invalid"}, {"--port", "-1"},
                {"--port", "65536"}, {"--no-open", "unexpected"},
                {"--port", "0", "--port", "0"}, {"--no-open", "--no-open"}
        };
        for (final var tail : invalid) {
            final var arguments = new java.util.ArrayList<>(List.of("simulator"));
            arguments.addAll(List.of(tail));
            output.reset();
            errors.reset();
            assertEquals(2, invoke(output, errors, arguments.toArray(String[]::new)));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage:"));
            assertFalse(errors.toString(StandardCharsets.UTF_8).contains("Exception in thread"));
        }
    }

    @Test
    void discoversSchemasAndRunnableExamplesWithoutReadingADataset() {
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "algorithms"));
        final var list = output.toString(StandardCharsets.UTF_8);
        assertTrue(list.contains("fitcount"));
        assertTrue(list.contains("catboost-top1"));
        assertTrue(list.contains("seed"));
        assertTrue(list.contains("--param"));
        output.reset();
        assertEquals(0, invoke(output, errors, "algorithms", "catboost-top1"));
        final var detail = output.toString(StandardCharsets.UTF_8);
        assertTrue(detail.contains("No algorithm parameters"));
        assertTrue(detail.contains("run --dataset"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runsPackagedCatBoostAndRandomAlgorithmsThroughTheCommonRunner() throws Exception {
        writeTrace(temporary, new Record[]{
                alloc(1, new int[]{1, 2}, 4, 4096), alloc(2, new int[]{1, 2}, 2, 1024),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2)},
                3, 4, -4, -3);
        for (final var algorithm : new String[]{"catboost-top1", "catboost-topn-fitcount",
                "catboost-rollout-top1", "catboost-rollout-topn-fitcount", "randomn-fitcount"}) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", algorithm), errors.toString(StandardCharsets.UTF_8));
            final var json = output.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"algorithm\":\"" + algorithm + "\""));
            final var className = switch (algorithm) {
                case "catboost-top1" -> "catboost.CatBoostTop1";
                case "catboost-topn-fitcount" -> "catboost.CatBoostTopNFitCount";
                case "catboost-rollout-top1" -> "catboost.CatBoostRolloutTop1";
                case "catboost-rollout-topn-fitcount" -> "catboost.CatBoostRolloutTopNFitCount";
                default -> "fitcount.RandomNFitCount";
            };
            assertTrue(json.contains("one.cloud.benchmark.algorithms." + className));
            assertTrue(json.contains("\"admitted_units\":2"));
            assertTrue(json.contains("\"committed_units\":2"));
            assertTrue(json.contains("\"complete\":true"));
            final var report = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(2, report.get("format_version").getAsInt());
            if (algorithm.startsWith("catboost")) {
                assertEquals(algorithm.endsWith("top1") ? 1 : 20, report.get("top_n").getAsInt());
            } else {
                assertEquals(20, report.getAsJsonObject("algorithm_parameters").get("topN").getAsInt());
            }
            final var hash = algorithm.contains("rollout")
                    ? "33187df07d340b52c60e635b78396bd0369845651684eee2bef3a079e7880174"
                    : "38da593f67c7531c963c0d09ea62eab0e1bc5ced168a0cf52a8e5e1b5ecac417";
            if (algorithm.startsWith("catboost")) {
                assertTrue(json.contains("\"model_sha256\":\"" + hash + "\""));
            } else {
                assertFalse(json.contains("\"model_sha256\""));
            }
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void externalModelsAreNotHiddenFlagsOrUndeclaredAlgorithmParameters() {
        for (final var arguments : List.of(
                new String[]{"run", "--dataset", "absent", "--algorithm", "catboost-top1",
                        "--model", "absent.cbm"},
                new String[]{"run", "--dataset", "absent", "--algorithm", "catboost-top1",
                        "--param", "model=absent.cbm"})) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(2, invoke(output, errors, arguments));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertFalse(errors.toString(StandardCharsets.UTF_8).contains("Run failed:"));
        }
    }

    @Test
    void bulkAndDpRetainAnnouncementWindowsAndAllNewMetrics() throws Exception {
        writeTrace(temporary, new Record[]{alloc(1, new int[]{1}, 1), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2)}, 2, 3, -3, -2);
        for (final var algorithm : List.of("bulk-fitcount", "dp")) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", algorithm, "--bulk-size", "100"), errors.toString(StandardCharsets.UTF_8));
            final var report = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            final var parameters = report.getAsJsonObject("algorithm_parameters");
            assertEquals(128, parameters.get("fitCap").getAsInt());
            assertEquals(42, parameters.get("seed").getAsLong());
            assertEquals(256, parameters.get("cacheRows").getAsInt());
            assertEquals(2, report.getAsJsonObject("creates").get("attempted_units").getAsInt());
            assertEquals(1, report.getAsJsonObject("creates").get("schedule_windows").getAsInt());
            assertEquals(1, report.getAsJsonObject("creates").get("admitted_units").getAsInt());
            assertEquals(1, report.getAsJsonObject("creates").get("declined_units").getAsInt());
            assertEquals("service-bursts-v1", report.get("announcement").getAsString());
            assertEquals("complete announced window", report.getAsJsonObject("creates").get("latency_scope").getAsString());
            assertTrue(report.has("algorithm_runtime_parameters"));
            assertTrue(report.has("algorithm_counters"));
            assertEquals(2, report.getAsJsonObject("logical_resources").getAsJsonObject("requested").get("1").getAsDouble());
            assertEquals(1, report.getAsJsonObject("logical_resources").getAsJsonObject("admitted").get("1").getAsDouble());
            assertEquals(1, report.getAsJsonObject("refused_resources_by_allocation_profile").get("2").getAsLong());
            assertEquals(64, report.get("parameter_digest").getAsString().length());
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
            output.reset();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", algorithm, "--bulk-size", "100", "--max-operations", "1"));
            final var prefix = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(prefix.get("truncated_window").getAsBoolean());
        }
    }

    @Test
    void rolloutUsesItsDeclaredParametersAndExplicitSignedPrior() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var prior = RolloutPriorProtos.Prior.newBuilder().setVersion("rollout-prior-v1").setDatasetId(42)
                .addBundles(RolloutPriorProtos.Bundle.newBuilder().setId(1).setWeight(1)
                        .addUnits(RolloutPriorProtos.Unit.newBuilder().setId(1)
                                .addMembers(RolloutPriorProtos.Member.newBuilder().setSourceResource(2))))
                .build();
        final var path = temporary.resolve("prior.pb");
        Files.write(path, prior.toBuilder().setSha256(ByteString.copyFrom(RolloutPrior.canonicalDigest(prior)))
                .build().toByteArray());
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "rollout",
                "--rollout-prior", path.toString(), "--param", "candidates=2", "--param", "scenarios=2",
                "--param", "horizon=1"), errors.toString(StandardCharsets.UTF_8));
        final var report = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(128, report.getAsJsonObject("algorithm_parameters").get("fitCap").getAsInt());
        assertTrue(report.getAsJsonObject("algorithm_runtime_parameters").has("prior_digest"));
        assertEquals(1, report.getAsJsonObject("creates").get("admitted_units").getAsInt());
        assertEquals(1, report.getAsJsonObject("creates").get("declined_units").getAsInt());
        assertEquals(2, report.getAsJsonObject("algorithm_counters").get("continuation_calls").getAsLong());
        final var alias = temporary.resolve("same-prior.pb");
        Files.copy(path, alias);
        output.reset();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "rollout",
                "--rollout-prior", alias.toString(), "--param", "horizon=1", "--param", "scenarios=2",
                "--param", "candidates=2"));
        final var repeated = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(report.get("parameter_digest"), repeated.get("parameter_digest"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void parameterDigestUsesCanonicalSettingsAndActualModelBytesInsteadOfArtifactPaths() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        for (final var algorithm : List.of("randomn-fitcount", "catboost-topn-fitcount", "catboost-rollout-topn-fitcount")) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", algorithm, "--param", "topN=1"));
            final var first = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            output.reset();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", algorithm, "--param", "topN=2"));
            final var second = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(first.get("parameter_digest").equals(second.get("parameter_digest")));
        }
        final var path = temporary.resolve("custom.cbm");
        final var selectedPath = new AtomicReference<Path>(path);
        final var selectedTopN = new AtomicInteger(1);
        AlgorithmRegistry.register("cli-model-bytes", "Explicit model factory", List.of(),
                (context, catalogue, parameters) -> new CatBoostTopNFitCount(context, 44, catalogue,
                        selectedTopN.get(), 10, 64, selectedPath.get()));
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final var digests = new java.util.ArrayList<String>();
        for (final var resource : List.of("rollout.cbm", "fitcount.cbm")) {
            try (final var model = getClass().getResourceAsStream("/one/cloud/benchmark/algorithms/catboost/" + resource)) {
                Files.copy(model, path, StandardCopyOption.REPLACE_EXISTING);
            }
            output.reset();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                    "--algorithm", "cli-model-bytes"), errors.toString(StandardCharsets.UTF_8));
            final var report = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(report.has("model_source"));
            digests.add(report.get("parameter_digest").getAsString());
        }
        assertFalse(digests.get(0).equals(digests.get(1)));
        selectedTopN.set(2);
        output.reset();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "cli-model-bytes"));
        final var changedTopN = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, changedTopN.get("top_n").getAsInt());
        assertFalse(digests.get(1).equals(changedTopN.get("parameter_digest").getAsString()));
        selectedTopN.set(1);
        final var alias = temporary.resolve("same-model.cbm");
        Files.copy(path, alias);
        selectedPath.set(alias);
        output.reset();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "cli-model-bytes"));
        final var report = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(digests.get(1), report.get("parameter_digest").getAsString());
        Files.writeString(alias, "invalid model");
        output.reset();
        assertEquals(1, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "cli-model-bytes"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
        selectedPath.set(null);
    }

    @Test
    void emitsOneReportWithInputIdentityOptionsAdmissionsAndOccupiedQuality() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final int status = invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fitcount",
                "--param", "seed=-9007199254740991", "--param", "fitCap=2", "--param", "cacheRows=1",
                "--probe-cap", "3");
        final var json = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, status);
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertTrue(json.startsWith("{\n"));
        assertTrue(json.endsWith("}" + System.lineSeparator()));
        assertTrue(json.contains("\"algorithm\":\"fitcount\""));
        assertTrue(json.contains("\"gpu_model\":\"devices20\""));
        assertTrue(json.contains("\"dataset_id\":\"42\""));
        assertTrue(json.contains("\"snapshot_sha256\":\""));
        assertTrue(json.contains("\"operations_sha256\":\""));
        final var report = JsonParser.parseString(json).getAsJsonObject();
        final var parameters = report.getAsJsonObject("algorithm_parameters");
        assertEquals(-9007199254740991L, parameters.get("seed").getAsLong());
        assertEquals(2, parameters.get("fitCap").getAsInt());
        assertEquals(1, parameters.get("cacheRows").getAsInt());
        assertEquals(3, report.get("probe_cap").getAsInt());
        assertFalse(report.has("seed"));
        assertFalse(report.has("copy_cap"));
        assertFalse(report.has("cache_rows"));
        assertTrue(json.contains("\"complete\":true"));
        assertTrue(json.contains("\"attempted_units\":2"));
        assertTrue(json.contains("\"admitted_units\":1"));
        assertTrue(json.contains("\"declined_units\":1"));
        assertTrue(json.contains("\"declined_unit_noops\":1"));
        assertTrue(json.contains("\"partial_unit_restarts\":"));
        assertFalse(json.contains("partial_churn_phases"));
        assertTrue(json.contains("\"operation\":2"));
        assertFalse(json.contains("\"operation\":4"));
        assertTrue(json.contains("\"probe_fit_counts\":[0,0]"));
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
    }

    @Test
    void selectsGeometricBestFitThroughTheSameReplay() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "geometric-best-fit"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"algorithm\":\"geometric-best-fit\""));
        assertTrue(json.contains("one.cloud.benchmark.algorithms.geometric.GeometricBestFit"));
        assertTrue(json.contains("\"attempted_units\":2"));
        assertTrue(json.contains("\"admitted_units\":1"));
        assertTrue(json.contains("\"declined_unit_noops\":1"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runsFgdWithDeviceAccountingAndRejectsAggregateMode() throws Exception {
        writeTrace(temporary, new Record[]{
                alloc(1, new int[]{11}, 3), alloc(2, new int[]{11}, .8), alloc(3, new int[]{11}, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2), resource(5, 3, 2)},
                2, 3, 4, 5, -5, -4, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fgd"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"algorithm\":\"fgd\""));
        assertTrue(json.contains("one.cloud.benchmark.algorithms.fgd.Fgd"));
        assertTrue(json.contains("\"gpu_model\":\"devices20\""));
        assertTrue(json.contains("\"admitted_units\":3"));
        assertTrue(json.contains("\"declined_units\":1"));
        assertTrue(json.contains("\"committed_units\":3"));
        assertTrue(json.contains("\"complete\":true"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(1, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fgd",
                "--gpu-model", "aggregate"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("devices20"));
    }

    @Test
    void fgdSupportsAnEmptyScenarioAndCatalogue() throws Exception {
        writeTrace(temporary, records());
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fgd"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"processed_operations\":0"));
        assertTrue(json.contains("\"catalogue_units\":0"));
        assertTrue(json.contains("\"mean\":null"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void algorithmsSupportingBothGpuModelsRespectDeviceAccounting() throws Exception {
        final var rows = new Record[]{
                alloc(1, new int[]{11}, 3), alloc(2, new int[]{11}, .8), alloc(3, new int[]{11}, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2), resource(5, 3, 2)};
        writeTrace(temporary, rows, 2, 3, 4, 5, -5, -4, -3, -2);
        for (final var algorithm : new String[]{"fitcount", "mean-fitcount", "geometric-best-fit", "cpu-ram-fgd",
                "k8s-least-allocated", "k8s-most-allocated", "k8s-requested-to-capacity-ratio", "k8s-balanced-allocation"}) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", algorithm));
            final var defaults = output.toString(StandardCharsets.UTF_8);
            assertTrue(defaults.contains("\"algorithm\":\"" + algorithm + "\""));
            assertTrue(defaults.contains("\"gpu_model\":\"devices20\""));
            assertTrue(defaults.contains("\"admitted_units\":3"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
            output.reset();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", algorithm,
                    "--gpu-model", "devices20"));
            final var devices = output.toString(StandardCharsets.UTF_8);
            assertTrue(devices.contains("\"gpu_model\":\"devices20\""));
            assertTrue(devices.contains("\"admitted_units\":3"));
            assertTrue(devices.contains("\"declined_units\":1"));
            assertTrue(devices.contains("\"committed_units\":3"));
            assertTrue(devices.contains("\"complete\":true"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
            assertEquals(defaults.lines().filter(line -> line.contains("\"scenario_digest\":")
                    || line.contains("\"result_checksum\":")).toList(),
                    devices.lines().filter(line -> line.contains("\"scenario_digest\":")
                            || line.contains("\"result_checksum\":")).toList());
            output.reset();
            assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", algorithm,
                    "--gpu-model", "aggregate"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"admitted_units\":4"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void unsupportedDeviceDemandsFailWithoutAResult() throws Exception {
        writeTrace(temporary, new Record[]{alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, 1.5),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2)}, 2, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(1, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
    }

    @Test
    void prefixIsExplicitAndDoesNotTruncateTheCatalogue() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fitcount",
                "--max-operations", "1"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"processed_operations\":1"));
        assertTrue(json.contains("\"normalized_operations\":4"));
        assertTrue(json.contains("\"catalogue_units\":2"));
        assertTrue(json.contains("\"complete\":false"));
        assertTrue(json.contains("\"operation\":1"));
    }

    @Test
    void helpAndUsageErrorsDoNotReadDatasetsOrWriteSuccessReports() {
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "--help"));
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("Usage:"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        final String[][] invalid = {
                {},
                {"--dataset", "absent", "--algorithm", "fitcount"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--seed", "44"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--fit-cap", "10"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--cache-rows", "64"},
                {"run", "--dataset", "absent", "--algorithm", "randomn-fitcount", "--top-n", "20"},
                {"run", "--dataset", "absent", "--algorithm", "unknown"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--bulk-size", "101"},
                {"run", "--dataset", "absent", "--algorithm", "rollout"},
                {"run", "--dataset", "absent", "--algorithm", "rollout", "--rollout-prior", "absent", "--bulk-size", "2"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--rollout-prior", "absent"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--param", "candidates=2"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--seed", "18446744073709551616"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--fit-cap", "0"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--cache-rows", "-1"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--max-operations", "0"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--catalogue-limit", "-1"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--gpu-model", "unknown"},
                {"run", "--dataset", "absent", "--algorithm", "randomn-fitcount", "--top-n", "0"},
                {"run", "--dataset", "absent", "--algorithm", "randomn-fitcount", "--model", "absent.cbm"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--top-n", "2"},
                {"run", "--dataset", "absent", "--algorithm", "catboost-top1", "--top-n", "2"},
                {"run", "--dataset", "absent", "--dataset", "another", "--algorithm", "fitcount"},
                {"run", "--dataset", "absent", "--algorithm"},
                {"run", "--dataset", "absent", "--algorithm", "fitcount", "--unknown", "1"}
        };
        for (final var arguments : invalid) {
            output.reset();
            errors.reset();
            assertEquals(2, invoke(output, errors, arguments));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage:"));
        }
    }

    @Test
    void missingInputFailsWithoutJsonAndEmptyTraceUsesNullLatencies() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(1, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
        writeTrace(temporary, records());
        output.reset();
        errors.reset();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"processed_operations\":0"));
        assertTrue(json.contains("\"mean\":null"));
        assertTrue(json.contains("\"quality_checkpoints\":[\n  ]"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void customSchemaControlsTypedParsingAndEscapingWithoutCliChanges() throws Exception {
        final var observed = new AtomicReference<Map<String, JsonPrimitive>>();
        final var label = "a b=c'$(noop)`x`";
        final var description = "A literal \"label\", not the scheduler's random seed.";
        AlgorithmRegistry.register("cli-custom-parameters", "Custom parameters", List.of(
                Parameter.text("seed", "A string called seed", description, label),
                Parameter.integer("width", "Width", "Width passed to the custom algorithm.", 2, 1, 10),
                Parameter.number("weight", "Weight", "Weight passed to the custom algorithm.", .5, 0, 1),
                Parameter.bool("enabled", "Enabled", "Enable the custom algorithm's optional behavior.", false)),
                (context, catalogue, parameters) -> {
                    observed.set(parameters);
                    return new GeometricBestFit(context, 44);
                });
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final var text = "literal=\"value\"\nsecond line";
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "cli-custom-parameters", "--param", "seed=" + text,
                "--param", "width=3e0", "--param", "weight=0.25", "--param", "enabled=true"),
                errors.toString(StandardCharsets.UTF_8));
        final var report = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        final var parameters = report.getAsJsonObject("algorithm_parameters");
        assertEquals(text, parameters.get("seed").getAsString());
        assertEquals(3, parameters.get("width").getAsInt());
        assertEquals(.25, parameters.get("weight").getAsDouble());
        assertTrue(parameters.get("enabled").getAsBoolean());
        assertEquals(text, observed.get().get("seed").getAsString());
        assertFalse(report.has("seed"));
        output.reset();
        assertEquals(0, invoke(output, errors, "algorithms", "cli-custom-parameters"));
        final var details = output.toString(StandardCharsets.UTF_8);
        assertTrue(details.contains("STRING"));
        assertTrue(details.contains("INTEGER"));
        assertTrue(details.contains("NUMBER"));
        assertTrue(details.contains("BOOLEAN"));
        assertTrue(details.contains("    " + description + System.lineSeparator()));
        assertTrue(details.contains("'seed=a b=c'\"'\"'$(noop)`x`'"));
        output.reset();
        assertEquals(0, invoke(output, errors, "run", "--dataset", temporary.toString(),
                "--algorithm", "cli-custom-parameters", "--param", "seed="));
        assertEquals("", observed.get().get("seed").getAsString());
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        for (final var invalid : List.of("enabled=TRUE", "enabled=1", "width=2.5", "weight=Infinity")) {
            output.reset();
            errors.reset();
            assertEquals(2, invoke(output, errors, "run", "--dataset", "absent",
                    "--algorithm", "cli-custom-parameters", "--param", invalid));
            assertFalse(errors.toString(StandardCharsets.UTF_8).contains("Run failed:"));
        }
    }

    @Test
    void rejectsTypedParameterErrorsBeforeTryingToReadInput() {
        final String[][] invalid = {
                {"--param", "seed=1.5"},
                {"--param", "seed=9007199254740992"},
                {"--param", "seed=NaN"},
                {"--param", "fitCap=0"},
                {"--param", "fitCap=101"},
                {"--param", "fitCap=true"},
                {"--param", "unknown=1"},
                {"--param", "seed=1", "--param", "seed=2"},
                {"--param", "seed"},
                {"--param", "=1"},
                {"--probe-cap", "0"}
        };
        for (final var tail : invalid) {
            final var arguments = new java.util.ArrayList<>(List.of(
                    "run", "--dataset", "absent", "--algorithm", "fitcount"));
            arguments.addAll(List.of(tail));
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(2, invoke(output, errors, arguments.toArray(String[]::new)));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertFalse(errors.toString(StandardCharsets.UTF_8).contains("Run failed:"));
        }
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(2, invoke(output, errors, "algorithms", "unknown"));
        assertEquals(2, invoke(output, errors, "algorithms", "fitcount", "extra"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    private static int invoke(ByteArrayOutputStream output, ByteArrayOutputStream errors, String... arguments) {
        try (final var out = new PrintStream(output, true, StandardCharsets.UTF_8);
                final var err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            return RunnerCli.run(arguments, out, err);
        }
    }

    private static Record[] records() {
        return new Record[]{alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2)};
    }
}
