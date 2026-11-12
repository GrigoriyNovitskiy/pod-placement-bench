package one.cloud.benchmark.algorithms.rl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.algorithms.fitcount.FitCount;
import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.runner.BenchmarkExperiment;
import one.cloud.benchmark.runner.DatasetRunner;
import one.cloud.benchmark.runner.Scenario;
import one.cloud.benchmark.simulator.ManualScenario;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;

public final class TrainingBridge {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final BufferedReader input;
    private final PrintWriter output;
    private final Map<Path, Scenario> scenarios = new LinkedHashMap<>();

    private TrainingBridge(BufferedReader input, PrintWriter output) {
        this.input = input;
        this.output = output;
    }

    public static void main(String[] arguments) {
        final int status = run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(BufferedReader input, PrintWriter output) {
        final var bridge = new TrainingBridge(input, output);
        bridge.send(Map.of("type", "ready", "protocolVersion", 1, "featureNames", RlFeatures.names()));
        try {
            for (String line; (line = input.readLine()) != null;) {
                bridge.episode(JsonParser.parseString(line).getAsJsonObject());
            }
            return 0;
        } catch (Exception failure) {
            bridge.send(Map.of("type", "error", "message",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            return 1;
        }
    }

    private void send(Object message) {
        output.println(JSON.toJson(message));
        output.flush();
    }

    private void episode(JsonObject command) throws Exception {
        final var path = Path.of(command.get("input").getAsString()).toRealPath();
        var scenario = scenarios.get(path);
        if (scenario == null) {
            if (Files.isDirectory(path)) {
                scenario = Scenario.from(Dataset.load(path.resolve("snapshot.pb"), path.resolve("operations.pb")));
            } else {
                try (final var reader = Files.newBufferedReader(path)) {
                    final var manual = ManualScenario.compile(JSON.fromJson(reader, ManualScenario.Config.class));
                    scenario = Scenario.fromAtomicEvents(manual.model(), manual.requests(), manual.events(),
                            manual.catalogue(), manual.digest());
                }
            }
            scenarios.put(path, scenario);
        }
        final long seed = command.has("seed") ? command.get("seed").getAsLong() : 42;
        final String id = command.has("algorithm") ? command.get("algorithm").getAsString() : "external";
        final var options = new BenchmarkExperiment.Options(integer(command, "maxOperations", 0),
                integer(command, "catalogueLimit", 32), 1, 0, seed);
        final var parameters = new LinkedHashMap<String, JsonPrimitive>();
        if (command.has("parameters")) {
            for (final var entry : command.getAsJsonObject("parameters").entrySet()) {
                parameters.put(entry.getKey(), entry.getValue().getAsJsonPrimitive());
            }
        }
        final var settings = id.equals("external") ? null
                : new BenchmarkExperiment.Algorithm(id, parameters, null).settings(seed, 1);
        final Episode[] active = new Episode[1];
        final var measurement = BenchmarkExperiment.measure(scenario, options, (context, ignored, catalogue) -> {
            active[0] = new Episode(context, seed, catalogue, settings);
            return active[0];
        });
        BenchmarkExperiment.Quality quality = null;
        if (command.has("quality") && command.get("quality").getAsBoolean()) {
            final var actions = active[0].actions;
            final DatasetRunner.Factory replay = settings == null ? (context, ignored, catalogue) -> {
                final int[] position = {0};
                return new FitCount(context, seed, new Request[0], 1, 1,
                        (request, plans, count) -> new int[]{actions.get(position[0]++)});
            } : BenchmarkExperiment.factory(settings, null);
            quality = BenchmarkExperiment.quality(scenario, options, replay);
            if (!quality.counts().checksum().equals(measurement.counts().checksum())) {
                throw new IllegalStateException("Quality replay differs from the measured episode");
            }
        }
        final var episode = active[0];
        final var metrics = new LinkedHashMap<String, Object>();
        metrics.put("input", path.toString());
        metrics.put("scenarioDigest", scenario.digest);
        metrics.put("hosts", scenario.model.hostCount);
        metrics.put("operations", measurement.counts().processedOperations());
        metrics.put("pods", episode.pods);
        metrics.put("shards", episode.shards);
        metrics.put("gpuPods", episode.gpuPods);
        metrics.put("attemptedPods", episode.attemptedPods);
        metrics.put("attemptedShards", episode.attemptedShards);
        metrics.put("attemptedGpuPods", episode.attemptedGpuPods);
        metrics.put("measurement", measurement);
        metrics.put("quality", quality);
        final var result = new LinkedHashMap<String, Object>();
        result.put("type", "result");
        result.put("reward", episode.pods - episode.rewardReported);
        result.put("action", episode.previousAction);
        result.put("metrics", metrics);
        send(result);
    }

    private static int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsBigDecimal().intValueExact() : fallback;
    }

    private final class Episode extends SchedulingAlgorithm {
        private final SchedulingAlgorithm delegate;
        private final RlFeatures features;
        private final List<Integer> actions = new ArrayList<>();
        private Assignment[][] pending;
        private Integer previousAction;
        private long pods, shards, gpuPods, attemptedPods, attemptedShards, attemptedGpuPods, rewardReported;

        private Episode(SchedulingContext context, long seed, Request[] catalogue,
                AlgorithmRegistry.Settings settings) throws Exception {
            super(context, seed);
            features = settings == null ? new RlFeatures(context) : null;
            delegate = settings == null ? new FitCount(context, seed, catalogue, 10, 64, this::select)
                    : AlgorithmRegistry.create(settings, context, catalogue);
        }

        private int[] select(Request request, Assignment[][] plans, int count) throws IOException {
            final var message = new LinkedHashMap<String, Object>();
            message.put("type", "decision");
            message.put("features", features.extract(request, plans, count));
            message.put("members", request.size());
            message.put("reward", pods - rewardReported);
            message.put("action", previousAction);
            send(message);
            rewardReported = pods;
            final String line = input.readLine();
            if (line == null) {
                throw new IOException("Action stream ended during an episode");
            }
            final var action = JsonParser.parseString(line).getAsJsonObject().getAsJsonPrimitive("action");
            pending = Arrays.copyOf(plans, count);
            if (action != null && action.isString() && action.getAsString().equals("teacher")) {
                final var all = new int[count];
                Arrays.setAll(all, index -> index);
                return all;
            }
            if (action == null || !action.isNumber()) {
                throw new IllegalArgumentException("Action must be an integer candidate index or 'teacher'");
            }
            final int selected = action.getAsBigDecimal().intValueExact();
            if (selected < 0 || selected >= count) {
                throw new IllegalArgumentException("Action is outside the candidate range");
            }
            return new int[]{selected};
        }

        @Override
        public void schedule(Request request) throws Exception {
            delegate.schedule(request);
            if (pending != null) {
                int selected = -1;
                for (int candidate = 0; candidate < pending.length; candidate++) {
                    if (matches(pending[candidate])) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected < 0) {
                    throw new IllegalStateException("Selected plan does not match any observed candidate");
                }
                previousAction = selected;
                actions.add(selected);
                pending = null;
            }
            final var model = context.model();
            for (int member = 0; member < request.size(); member++) {
                final int resource = request.resource(member);
                final int kind = model.rules.kind[model.constraints[resource]];
                final boolean placed = context.placement(resource) != null;
                if (kind == POD_VALUE) {
                    attemptedPods++;
                    pods += placed ? 1 : 0;
                    final boolean gpu = model.gpus != null
                            && (model.gpus.demand(resource, 11) > 0 || model.gpus.demand(resource, 17) > 0);
                    attemptedGpuPods += gpu ? 1 : 0;
                    gpuPods += gpu && placed ? 1 : 0;
                } else if (kind == SHARD_VALUE) {
                    attemptedShards++;
                    shards += placed ? 1 : 0;
                }
            }
        }

        private boolean matches(Assignment[] candidate) {
            for (final var expected : candidate) {
                final var actual = context.placement(expected.resource());
                if (actual == null || actual.host() != expected.host() || actual.numa() != expected.numa()
                        || !Arrays.equals(actual.diskOffsets(), expected.diskOffsets())
                        || !Arrays.equals(actual.devices(), expected.devices())
                        || !Arrays.equals(actual.gpus(), expected.gpus())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public long plannerCalls() {
            return delegate.plannerCalls();
        }

        @Override
        public Map<String, String> parameters() {
            return delegate.parameters();
        }

        @Override
        public Map<String, Long> counters() {
            return delegate.counters();
        }

        @Override
        public void placed(Assignment placement) {
            delegate.placed(placement);
        }

        @Override
        public void removed(Assignment placement) {
            delegate.removed(placement);
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
