package one.cloud.benchmark.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;

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
                {"--dataset", "absent", "--dataset", "another", "--algorithm", "fitcount"},
                {"--dataset", "absent", "--algorithm"},
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
