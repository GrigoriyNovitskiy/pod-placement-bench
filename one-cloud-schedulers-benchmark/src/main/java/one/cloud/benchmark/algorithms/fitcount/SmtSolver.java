package one.cloud.benchmark.algorithms.fitcount;

import java.io.BufferedReader;
import java.io.File;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class SmtSolver implements AutoCloseable {
    record Row(long[] coefficients, long capacity) {
    }

    static final class Limit extends RuntimeException {
        Limit() {
            super("smt_resource_limit", null, false, false);
        }
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("Z3 executable is unavailable on PATH");
        }
    }

    private static final int MAX_RESPONSE = 1_048_576;
    private static final Pattern RESOURCE_COUNT = Pattern.compile(":rlimit-count\\s+(\\d+)");
    private static final Pattern MODEL = Pattern.compile("\\(\\s*(?:\\(p\\d+\\s+(?:true|false)\\)\\s*)*\\)");
    private static final Pattern VALUE = Pattern.compile("\\(p(\\d+)\\s+(true|false)\\)");
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, task -> {
        final var thread = new Thread(task, "smt-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private static String executableVersion;

    private final Process process;
    private final BufferedReader output;
    private final BufferedWriter input;
    private final ScheduledFuture<?> watchdog;
    private final int[] choiceCounts;
    private final int variableCount, resourceLimit;
    private volatile boolean timedOut;
    private long usedResources;
    private int exchanges;

    SmtSolver(int[] choiceCounts, List<Row> rows, int resourceLimit) {
        if (choiceCounts.length == 0 || choiceCounts.length > 8 || resourceLimit <= 0) {
            throw new IllegalArgumentException("SMT requires one to eight units and a positive resource limit");
        }
        this.choiceCounts = choiceCounts.clone();
        this.resourceLimit = resourceLimit;
        int total = 0;
        for (final int count : choiceCounts) {
            if (count < 0 || count > 128) {
                throw new IllegalArgumentException("Invalid SMT plan count");
            }
            total = Math.addExact(total, count);
        }
        if (total > 128) {
            throw new IllegalArgumentException("SMT supports at most 128 plans");
        }
        variableCount = total;
        for (final var row : rows) {
            if (row.coefficients.length != total || row.capacity < 0) {
                throw new IllegalArgumentException("Invalid SMT capacity row");
            }
            for (final long coefficient : row.coefficients) {
                if (coefficient < 0) {
                    throw new IllegalArgumentException("Negative SMT resource consumption");
                }
            }
        }
        process = start("-in", "-smt2");
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        watchdog = WATCHDOG.schedule(() -> {
            timedOut = true;
            process.destroyForcibly();
        }, 30, TimeUnit.SECONDS);
        try {
            initialize(rows);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static Process start(String... arguments) {
        final String executable = File.separatorChar == '\\' ? "z3.exe" : "z3";
        for (final String directory : System.getenv().getOrDefault("PATH", "").split(Pattern.quote(File.pathSeparator), -1)) {
            final Path candidate = Path.of(directory, executable).toAbsolutePath();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                final var command = new ArrayList<String>();
                command.add(candidate.toString());
                command.addAll(List.of(arguments));
                try {
                    return new ProcessBuilder(command).redirectErrorStream(true).start();
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot start Z3", failure);
                }
            }
        }
        throw new Unavailable();
    }

    static synchronized String version() {
        if (executableVersion != null) {
            return executableVersion;
        }
        final Process query = start("-version");
        try {
            if (!query.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Z3 version query timed out");
            }
            final byte[] bytes = query.getInputStream().readNBytes(4097);
            final String result = new String(bytes, StandardCharsets.UTF_8).trim();
            if (query.exitValue() != 0 || bytes.length > 4096 || !result.startsWith("Z3 version ")) {
                throw new IllegalStateException("Invalid Z3 version response");
            }
            executableVersion = result;
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Z3 version", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Z3 version query interrupted", interrupted);
        } finally {
            query.destroyForcibly();
        }
    }

    int[] next(int minimumAdmission) {
        if (minimumAdmission < 0 || minimumAdmission > choiceCounts.length) {
            throw new IllegalArgumentException("Invalid minimum admission");
        }
        if (usedResources >= resourceLimit) {
            throw new Limit();
        }
        final String response = exchange("(set-option :rlimit " + (resourceLimit - usedResources) + ")\n"
                + "(assert (>= admitted " + minimumAdmission + "))\n(check-sat)\n(get-info :all-statistics)\n");
        updateResourceCount(response);
        final String status = response.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        if (status.equals("unsat")) {
            return null;
        }
        if (status.equals("unknown")) {
            final String reason = exchange("(get-info :reason-unknown)\n");
            if (reason.contains("resource") || usedResources >= resourceLimit) {
                throw new Limit();
            }
            throw new IllegalStateException("Z3 returned UNKNOWN: " + reason.trim());
        }
        if (!status.equals("sat")) {
            throw new IllegalStateException("Unexpected Z3 status: " + status);
        }
        final var selected = new boolean[variableCount];
        if (variableCount != 0) {
            final StringBuilder values = new StringBuilder("(get-value (");
            for (int index = 0; index < variableCount; index++) {
                values.append(" p").append(index);
            }
            final String model = exchange(values.append("))\n").toString()).trim();
            if (!MODEL.matcher(model).matches()) {
                throw new IllegalStateException("Malformed Z3 model");
            }
            final var result = VALUE.matcher(model);
            final var seen = new boolean[variableCount];
            int count = 0;
            while (result.find()) {
                final int index = Integer.parseInt(result.group(1));
                if (index >= variableCount || seen[index]) {
                    throw new IllegalStateException("Invalid Z3 plan values");
                }
                selected[index] = Boolean.parseBoolean(result.group(2));
                seen[index] = true;
                count++;
            }
            if (count != variableCount) {
                throw new IllegalStateException("Incomplete Z3 model");
            }
        }
        final int[] choices = new int[choiceCounts.length];
        Arrays.fill(choices, -1);
        int offset = 0;
        for (int unit = 0; unit < choices.length; unit++) {
            for (int plan = 0; plan < choiceCounts[unit]; plan++) {
                if (selected[offset + plan]) {
                    if (choices[unit] >= 0) {
                        throw new IllegalStateException("Z3 selected multiple plans for a unit");
                    }
                    choices[unit] = plan;
                }
            }
            offset += choiceCounts[unit];
        }
        if (Arrays.stream(choices).filter(choice -> choice >= 0).count() < minimumAdmission) {
            throw new IllegalStateException("Z3 model violates minimum admission");
        }
        final StringBuilder block = new StringBuilder("(assert (not (and true");
        for (int index = 0; index < selected.length; index++) {
            block.append(selected[index] ? " p" + index : " (not p" + index + ")");
        }
        updateResourceCount(exchange(block.append(")))\n(get-info :all-statistics)\n").toString()));
        return choices;
    }

    private void initialize(List<Row> rows) {
        final StringBuilder script = new StringBuilder("(set-option :print-success false)\n"
                + "(set-option :produce-models true)\n(set-logic QF_LIA)\n");
        final var admissions = new ArrayList<String>();
        int offset = 0;
        for (final int count : choiceCounts) {
            final StringBuilder unit = new StringBuilder("(+ 0");
            for (int plan = 0; plan < count; plan++) {
                final String name = "p" + offset++;
                script.append("(declare-fun ").append(name).append(" () Bool)\n");
                unit.append(" (ite ").append(name).append(" 1 0)");
            }
            unit.append(')');
            script.append("(assert (<= ").append(unit).append(" 1))\n");
            admissions.add(unit.toString());
        }
        script.append("(define-fun admitted () Int (+ 0 ").append(String.join(" ", admissions)).append("))\n");
        for (final var row : rows) {
            script.append("(assert (<= (+ 0");
            for (int index = 0; index < variableCount; index++) {
                if (row.coefficients[index] != 0) {
                    script.append(" (ite p").append(index).append(' ').append(row.coefficients[index]).append(" 0)");
                }
            }
            script.append(") ").append(row.capacity).append("))\n");
        }
        script.append("(maximize admitted)\n(get-info :all-statistics)\n");
        updateResourceCount(exchange(script.toString()));
    }

    private void updateResourceCount(String response) {
        final var statistics = RESOURCE_COUNT.matcher(response);
        if (!statistics.find()) {
            throw new IllegalStateException("Z3 omitted its resource counter");
        }
        usedResources = Long.parseLong(statistics.group(1));
    }

    private String exchange(String commands) {
        final String marker = "smt-response-" + exchanges++;
        final StringBuilder response = new StringBuilder();
        final StringBuilder line = new StringBuilder();
        try {
            input.write(commands);
            input.write("(echo \"" + marker + "\")\n");
            input.flush();
            for (int character; (character = output.read()) != -1;) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("SMT operation interrupted");
                }
                if (character == '\n') {
                    if (line.toString().equals(marker)) {
                        if (timedOut) {
                            throw new IllegalStateException("Z3 exceeded its 30-second watchdog");
                        }
                        final String result = response.toString();
                        if (result.contains("max. resource limit exceeded")) {
                            throw new Limit();
                        }
                        if (result.contains("(error ")) {
                            throw new IllegalStateException("Z3 rejected the model: " + result.trim());
                        }
                        return result;
                    }
                    response.append(line).append('\n');
                    line.setLength(0);
                } else if (character != '\r') {
                    line.append((char) character);
                }
                if (response.length() + line.length() > MAX_RESPONSE) {
                    throw new IllegalStateException("Z3 response exceeded its size limit");
                }
            }
            throw new IllegalStateException(timedOut ? "Z3 exceeded its 30-second watchdog"
                    : "Z3 exited before completing its response: " + response + line);
        } catch (IOException failure) {
            throw new IllegalStateException(timedOut ? "Z3 exceeded its 30-second watchdog"
                    : "Z3 communication failed", failure);
        }
    }

    @Override
    public void close() {
        watchdog.cancel(false);
        process.destroyForcibly();
        try {
            input.close();
            output.close();
        } catch (IOException ignored) {
            // The process may already have closed its pipes after interruption.
        }
    }
}
