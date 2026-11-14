package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.GpuTopology;
import one.cloud.benchmark.model.Request;

public final class Catalogue {
    static final long HASH_START = 0xcbf29ce484222325L;
    private final Request[] requests;
    private final int[] frequencies;
    private final int population;
    private final Map<String, String> parameters;

    private Catalogue(Request[] requests, int[] frequencies, int population, Map<String, String> parameters) {
        this.requests = requests;
        this.frequencies = frequencies;
        this.population = population;
        this.parameters = parameters;
    }

    public static Catalogue sampled(Request[] selected) {
        final var requests = selected.clone();
        for (final var request : requests) {
            Objects.requireNonNull(request);
        }
        return new Catalogue(requests, null, requests.length, Map.of(
                "catalogue_weight_mode", "sampled", "catalogue_population", Integer.toString(requests.length),
                "catalogue_selected", Integer.toString(requests.length),
                "catalogue_weight_mass", Integer.toString(requests.length)));
    }

    public static Catalogue full(ClusterSpec model, Request[] population, Request[] selected) {
        final var requests = selected.clone();
        final var families = new LinkedHashMap<Key, Integer>();
        final int[] indices = new int[requests.length];
        for (int index = 0; index < requests.length; index++) {
            final var key = key(model, requests[index]);
            indices[index] = families.computeIfAbsent(key, ignored -> families.size());
        }
        final int[] counts = new int[families.size()];
        for (final var request : population) {
            final var index = families.get(key(model, request));
            if (index != null) {
                counts[index]++;
            }
        }
        final int[] frequencies = new int[requests.length];
        for (int index = 0; index < requests.length; index++) {
            frequencies[index] = counts[indices[index]];
            if (frequencies[index] == 0) {
                throw new IllegalArgumentException("Selected physical shape is absent from the population");
            }
        }
        long mass = 0, checksum = HASH_START;
        for (final var family : families.entrySet()) {
            final int frequency = counts[family.getValue()];
            mass += frequency;
            checksum = checksum(checksum, family.getKey(), frequency);
        }
        return new Catalogue(requests, frequencies, population.length,
                parameters("full", population.length, requests.length, families.size(), mass, checksum));
    }

    public int size() {
        return requests.length;
    }

    public Request request(int index) {
        return requests[index];
    }

    public Request[] requests() {
        return requests.clone();
    }

    public boolean hasFullFrequencies() {
        return frequencies != null;
    }

    /** Full-mode frequencies are absolute per physical shape; repeated representatives must not be summed. */
    public int frequency(int index) {
        return frequencies == null ? 1 : frequencies[index];
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    Map<String, String> parameters(int families, long mass, long checksum) {
        return parameters(hasFullFrequencies() ? "full" : "sampled", population, requests.length,
                families, mass, checksum);
    }

    private static Map<String, String> parameters(String mode, int population, int selected, int families,
            long mass, long checksum) {
        return Map.of("catalogue_weight_mode", mode, "catalogue_population", Integer.toString(population),
                "catalogue_selected", Integer.toString(selected), "catalogue_families", Integer.toString(families),
                "catalogue_weight_mass", Long.toString(mass),
                "catalogue_weight_checksum", Long.toUnsignedString(checksum, 16));
    }

    static long checksum(long checksum, Key key, int frequency) {
        for (final long value : key.values) {
            checksum = (checksum ^ value) * 0x100000001b3L;
        }
        return (checksum ^ frequency) * 0x100000001b3L;
    }

    static Key key(ClusterSpec model, Request request) {
        final var rules = model.rules;
        final var allocations = model.allocations;
        final var members = new HashMap<Integer, Integer>();
        final int[] components = new int[request.size()];
        int length = 1;
        for (int index = 0; index < request.size(); index++) {
            final int resource = request.resource(index);
            if (resource >= model.alloc.length) {
                throw new IllegalArgumentException("Unknown catalogue resource");
            }
            final int profile = model.constraints[resource];
            if (rules.kind[profile] != 4 && rules.kind[profile] != 5) {
                throw new IllegalArgumentException("Catalogue members must be pods or shards");
            }
            members.put(resource, index);
            components[index] = index;
            final int alloc = model.alloc[resource];
            length = Math.addExact(length, 4 + 2 * (allocations.offset[alloc + 1] - allocations.offset[alloc])
                    + 12 * (rules.diskOffset[profile + 1] - rules.diskOffset[profile]));
        }
        for (int index = 0; index < request.size(); index++) {
            final int profile = model.constraints[request.resource(index)];
            for (int link = rules.linkOffset[profile]; link < rules.linkOffset[profile + 1]; link++) {
                final var target = members.get(rules.linkTarget[link]);
                if (rules.linkKind[link] == 0 && rules.linkDomain[link] == 2 && target != null) {
                    final int first = root(components, index);
                    final int second = root(components, target);
                    components[Math.max(first, second)] = Math.min(first, second);
                }
            }
        }
        final long[] values = new long[length];
        int offset = 0;
        values[offset++] = request.size();
        for (int index = 0; index < request.size(); index++) {
            final int resource = request.resource(index);
            final int profile = model.constraints[resource];
            final int alloc = model.alloc[resource];
            values[offset++] = rules.kind[profile];
            values[offset++] = root(components, index);
            values[offset++] = allocations.offset[alloc + 1] - allocations.offset[alloc];
            for (int value = allocations.offset[alloc]; value < allocations.offset[alloc + 1]; value++) {
                final int dimension = allocations.type[value];
                final double amount = model.gpus != null && (dimension == 11 || dimension == 17)
                        ? model.gpus.demand(resource, dimension) / (double) GpuTopology.SHARES : allocations.amount[value];
                values[offset++] = dimension;
                values[offset++] = Double.doubleToLongBits(amount);
            }
            values[offset++] = rules.diskOffset[profile + 1] - rules.diskOffset[profile];
            for (int disk = rules.diskOffset[profile]; disk < rules.diskOffset[profile + 1]; disk++) {
                values[offset++] = rules.diskMedia[disk];
                values[offset++] = rules.diskLayout[disk];
                values[offset++] = rules.diskExtents[disk];
                values[offset++] = Double.doubleToLongBits(rules.diskSpindles[disk]);
                values[offset++] = Double.doubleToLongBits(rules.diskIops[disk]);
                values[offset++] = rules.minDevices[disk];
                values[offset++] = rules.maxDevices[disk];
                values[offset++] = rules.minStripes[disk];
                values[offset++] = rules.maxStripes[disk];
                values[offset++] = rules.diskFlags[disk];
                values[offset++] = rules.metadataExtents[disk];
                values[offset++] = Double.doubleToLongBits(rules.diskIopsCap[disk]);
            }
        }
        return new Key(values);
    }

    private static int root(int[] components, int index) {
        while (components[index] != index) {
            index = components[index];
        }
        return index;
    }

    record Key(long[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }
}
