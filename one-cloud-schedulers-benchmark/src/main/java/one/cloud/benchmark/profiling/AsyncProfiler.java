package one.cloud.benchmark.profiling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class AsyncProfiler {
    public enum Event {
        CPU("cpu"), ALLOC("alloc"), WALL("wall");

        private final String option;

        Event(String option) {
            this.option = option;
        }

        public static Event parse(String value) {
            return switch (value) {
                case "cpu" -> CPU;
                case "alloc" -> ALLOC;
                case "wall" -> WALL;
                default -> throw new IllegalArgumentException("Profile event must be cpu, alloc or wall");
            };
        }
    }

    public enum Format {
        JFR("jfr"), HTML("html");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public static Format parse(String value) {
            return switch (value) {
                case "jfr" -> JFR;
                case "html" -> HTML;
                default -> throw new IllegalArgumentException("Profile format must be jfr or html");
            };
        }

        public static Format forPath(Path path) {
            final String name = path.getFileName() == null ? "" : path.getFileName().toString();
            final int dot = name.lastIndexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("--profile must end in .jfr or .html");
            }
            return parse(name.substring(dot + 1));
        }
    }

    private final Path asprof;
    private final Path jfrconv;

    public AsyncProfiler(Path home) {
        final var bin = home.toAbsolutePath().resolve("bin");
        asprof = bin.resolve("asprof");
        jfrconv = bin.resolve("jfrconv");
    }

    public static AsyncProfiler configured() {
        final var home = System.getenv("ASYNC_PROFILER_HOME");
        return home == null || home.isBlank() ? null : new AsyncProfiler(Path.of(home));
    }

    public boolean supports(Format format) {
        return Files.isRegularFile(asprof) && Files.isExecutable(asprof)
                && (format == Format.JFR || Files.isRegularFile(jfrconv) && Files.isExecutable(jfrconv));
    }

    public <T> Capture<T> capture(Event event, Format format, Callable<T> work) throws Exception {
        return capture(event, format, null, work);
    }

    public <T> Capture<T> capture(Event event, Format format, String stepFrame, Callable<T> work) throws Exception {
        if (!supports(format)) {
            throw new IllegalStateException("Set ASYNC_PROFILER_HOME to an async-profiler distribution with "
                    + (format == Format.HTML ? "bin/asprof and bin/jfrconv" : "bin/asprof"));
        }
        final var directory = Files.createTempDirectory("placement-profile-");
        try {
            final var recording = directory.resolve("profile.jfr");
            final var log = directory.resolve("command.log");
            final String pid = Long.toString(ProcessHandle.current().pid());
            final var start = new ArrayList<>(List.of(asprof.toString(), "start", "-e", event.option,
                    "-o", "jfr", "-f", recording.toString()));
            if (stepFrame != null) {
                start.addAll(switch (event) {
                    case CPU, WALL -> List.of("-i", "1ms");
                    case ALLOC -> List.of("--alloc", "64k");
                });
            }
            start.add(pid);
            final var stopCommand = List.of(asprof.toString(), "stop", pid);
            final AutoCloseable stop = () -> command(log, stopCommand);
            final T result;
            synchronized (AsyncProfiler.class) {
                command(log, start);
                try (stop) {
                    result = work.call();
                }
            }
            if (Files.size(recording) == 0) {
                throw new IOException("async-profiler produced an empty recording");
            }
            final var output = directory.resolve("profile." + format.extension);
            if (format == Format.HTML) {
                final var convert = new ArrayList<>(List.of(jfrconv.toString(), "--" + event.option));
                if (stepFrame != null) {
                    convert.addAll(List.of("--include", Pattern.quote(stepFrame)));
                }
                convert.add(recording.toString());
                if (stepFrame != null) {
                    final var samples = directory.resolve("profile.collapsed");
                    convert.add(samples.toString());
                    command(log, convert);
                    if (Files.size(samples) == 0) {
                        throw new NoSamplesException();
                    }
                    convert.removeLast();
                }
                convert.add(output.toString());
                command(log, convert);
                if (Files.size(output) == 0) {
                    throw new IOException("jfrconv produced an empty flame graph");
                }
            }
            return new Capture<>(result, output);
        } catch (Exception | Error failure) {
            try {
                cleanup(directory);
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private static void command(Path log, List<String> arguments) throws IOException, InterruptedException {
        final var executable = Path.of(arguments.getFirst()).getFileName();
        final var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException(executable + " timed out");
            }
            if (process.exitValue() != 0) {
                try (final var input = Files.newInputStream(log)) {
                    final var detail = new String(input.readNBytes(4096), StandardCharsets.UTF_8).strip();
                    throw new IOException(executable + " exited with status " + process.exitValue()
                            + (detail.isEmpty() ? "" : ": " + detail));
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void cleanup(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve("command.log"));
        Files.deleteIfExists(directory.resolve("profile.html"));
        Files.deleteIfExists(directory.resolve("profile.collapsed"));
        Files.deleteIfExists(directory.resolve("profile.jfr"));
        Files.deleteIfExists(directory);
    }

    public static final class NoSamplesException extends IOException {
        private NoSamplesException() {
            super("No samples matched the profiled step");
        }
    }

    public record Capture<T>(T value, Path file) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            cleanup(file.getParent());
        }
    }
}
