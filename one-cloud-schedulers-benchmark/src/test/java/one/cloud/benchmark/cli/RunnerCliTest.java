package one.cloud.benchmark.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;

import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.RolloutPriorProtos;
import one.cloud.benchmark.algorithms.fitcount.RolloutPrior;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerCliTest {
    @TempDir
    Path temporary;

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
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(),
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
            assertTrue(json.contains("\"top_n\":" + (algorithm.endsWith("top1") ? 1 : 20)));
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
        for (final var algorithm : new String[]{"randomn-fitcount", "catboost-topn-fitcount",
                "catboost-rollout-topn-fitcount"}) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm,
                    "--top-n", "1"));
            final var first = parameterDigest(output.toString(StandardCharsets.UTF_8));
            output.reset();
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm,
                    "--top-n", "2"));
            assertNotEquals(first, parameterDigest(output.toString(StandardCharsets.UTF_8)));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void externalModelIsIdentifiedByItsLoadedBytesAndInvalidModelsFail() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var model = temporary.resolve("custom.cbm");
        try (final var input = getClass().getResourceAsStream(
                "/one/cloud/benchmark/algorithms/catboost/rollout.cbm")) {
            Files.copy(input, model);
        }
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(),
                "--algorithm", "catboost-top1", "--model", model.toString()));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"model_source\":\"external\""));
        assertTrue(json.contains("33187df07d340b52c60e635b78396bd0369845651684eee2bef3a079e7880174"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        final var rolloutDigest = parameterDigest(json);
        try (final var input = getClass().getResourceAsStream(
                "/one/cloud/benchmark/algorithms/catboost/fitcount.cbm")) {
            Files.copy(input, model, StandardCopyOption.REPLACE_EXISTING);
        }
        output.reset();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(),
                "--algorithm", "catboost-top1", "--model", model.toString()));
        final var fitcountDigest = parameterDigest(output.toString(StandardCharsets.UTF_8));
        assertNotEquals(rolloutDigest, fitcountDigest);
        final var alias = temporary.resolve("same-model.cbm");
        Files.copy(model, alias);
        output.reset();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(),
                "--algorithm", "catboost-top1", "--model", alias.toString()));
        assertEquals(fitcountDigest, parameterDigest(output.toString(StandardCharsets.UTF_8)));
        Files.writeString(model, "invalid model");
        output.reset();
        assertEquals(1, invoke(output, errors, "--dataset", temporary.toString(),
                "--algorithm", "catboost-top1", "--model", model.toString()));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
    }

    @Test
    void selectsBulkAndDpWithExplicitWindowsAndNewProfileDefaults() throws Exception {
        final var records = new Record[]{alloc(1, new int[]{1}, 1), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2)};
        writeTrace(temporary, records, 2, 3, -3, -2);
        for (final var algorithm : new String[]{"bulk-fitcount", "dp"}) {
            final var output = new ByteArrayOutputStream();
            final var errors = new ByteArrayOutputStream();
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm,
                    "--bulk-size", "100"));
            final var json = output.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"copy_cap\":128"));
            assertTrue(json.contains("\"attempted_units\":2"));
            assertTrue(json.contains("\"schedule_windows\":1"));
            assertTrue(json.contains("\"admitted_units\":1"));
            assertTrue(json.contains("\"declined_units\":1"));
            assertTrue(json.contains("\"announcement\":\"service-bursts-v1\""));
            assertTrue(json.contains("\"algorithm_parameters\":"));
            assertTrue(json.contains("\"parameter_digest\":\""));
            assertTrue(json.contains("\"logical_resources\":{\"requested\":{\"1\":2.0},\"admitted\":{\"1\":1.0}}"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rolloutUsesAnExplicitSignedPriorWithIndependentVirtualInstances() throws Exception {
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
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "rollout",
                "--rollout-prior", path.toString(), "--rollout-candidates", "2", "--rollout-scenarios", "2",
                "--rollout-horizon", "1"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"algorithm\":\"rollout\""));
        assertTrue(json.contains("\"copy_cap\":128"));
        assertTrue(json.contains("\"prior_digest\":\""));
        assertTrue(json.contains("\"admitted_units\":1"));
        assertTrue(json.contains("\"declined_units\":1"));
        assertTrue(json.contains("\"continuation_calls\":2"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emitsOneReportWithInputIdentityOptionsAdmissionsAndOccupiedQuality() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final int status = invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fitcount",
                "--seed", "-9007199254740993", "--fit-cap", "2", "--cache-rows", "1");
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
        assertTrue(json.contains("\"seed\":\"-9007199254740993\""));
        assertTrue(json.contains("\"copy_cap\":2"));
        assertTrue(json.contains("\"cache_rows\":1"));
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
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(),
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
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fgd"));
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
        assertEquals(1, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fgd",
                "--gpu-model", "aggregate"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("devices20"));
    }

    @Test
    void fgdSupportsAnEmptyScenarioAndCatalogue() throws Exception {
        writeTrace(temporary, records());
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fgd"));
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
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm));
            final var defaults = output.toString(StandardCharsets.UTF_8);
            assertTrue(defaults.contains("\"algorithm\":\"" + algorithm + "\""));
            assertTrue(defaults.contains("\"gpu_model\":\"devices20\""));
            assertTrue(defaults.contains("\"admitted_units\":3"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
            output.reset();
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm,
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
            assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", algorithm,
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
        assertEquals(1, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
    }

    @Test
    void prefixIsExplicitAndDoesNotTruncateTheCatalogue() throws Exception {
        writeTrace(temporary, records(), 2, 3, -3, -2);
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fitcount",
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
                {"--dataset", "absent", "--algorithm", "unknown"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--seed", "18446744073709551616"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--fit-cap", "0"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--cache-rows", "-1"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--max-operations", "0"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--catalogue-limit", "-1"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--gpu-model", "unknown"},
                {"--dataset", "absent", "--algorithm", "randomn-fitcount", "--top-n", "0"},
                {"--dataset", "absent", "--algorithm", "randomn-fitcount", "--model", "absent.cbm"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--top-n", "2"},
                {"--dataset", "absent", "--algorithm", "catboost-top1", "--top-n", "2"},
                {"--dataset", "absent", "--dataset", "another", "--algorithm", "fitcount"},
                {"--dataset", "absent", "--algorithm"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--bulk-size", "101"},
                {"--dataset", "absent", "--algorithm", "rollout"},
                {"--dataset", "absent", "--algorithm", "rollout", "--rollout-prior", "absent", "--bulk-size", "2"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--rollout-prior", "absent"},
                {"--dataset", "absent", "--algorithm", "fitcount", "--unknown", "1"}
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
        assertEquals(1, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).startsWith("Run failed:"));
        writeTrace(temporary, records());
        output.reset();
        errors.reset();
        assertEquals(0, invoke(output, errors, "--dataset", temporary.toString(), "--algorithm", "fitcount"));
        final var json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"processed_operations\":0"));
        assertTrue(json.contains("\"mean\":null"));
        assertTrue(json.contains("\"quality_checkpoints\":[\n  ]"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    private static String parameterDigest(String json) {
        final var key = "\"parameter_digest\":\"";
        final int start = json.indexOf(key);
        assertTrue(start >= 0);
        return json.substring(start + key.length(), start + key.length() + 64);
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
