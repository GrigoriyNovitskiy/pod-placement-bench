package one.cloud.benchmark.simulator;

import java.util.LinkedHashMap;
import java.util.Map;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.runner.Scenario;

public record ScenarioInput(ClusterSpec model, Request[] requests, int[] events, Map<Integer, String> names,
        String[] requestNames, Request[] catalogue, String source, String digest,
        int rawOperations, int partialChurn, int expandedRestarts) {
    public static ScenarioInput from(Scenario scenario) {
        final var names = new LinkedHashMap<Integer, String>();
        for (int resource = 1; resource < scenario.model.alloc.length; resource++) {
            final var kind = Kind.forNumber(scenario.model.rules.kind[scenario.model.constraints[resource]]);
            names.put(resource, kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + resource);
        }
        final var requestNames = new String[scenario.requests.length];
        for (int unit = 0; unit < requestNames.length; unit++) {
            requestNames[unit] = names.get(scenario.requests[unit].resource(0));
        }
        final int size = Math.min(32, scenario.requests.length);
        final var catalogue = new Request[size];
        for (int index = 0; index < size; index++) {
            catalogue[index] = scenario.requests[(int) ((long) index * scenario.requests.length / size)];
        }
        return new ScenarioInput(scenario.model, scenario.requests, scenario.events, Map.copyOf(names),
                requestNames, catalogue, "dataset", scenario.digest, scenario.rawOperations,
                scenario.partialChurn, scenario.expandedRestarts);
    }
}
