package one.cloud.schedulers.algorithms;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;

import one.cloud.schedulers.engine.PlacementContext;
import one.cloud.schedulers.engine.PlacementPlanner;
import one.cloud.schedulers.model.PlacementModel;
import one.cloud.schedulers.model.PlacementRequest;

final class FitCountCache {
    private final PlacementContext context;
    private final PlacementPlanner planner;
    private final int cap;
    private final int maxCachedShapes;
    private final Sample[] samples;
    private final Sample[] pending;
    private final PriorityQueue<Sample> ready;
    private final LinkedHashMap<Sample, int[]> rows = new LinkedHashMap<>(16, .75f, true);
    private final int[] previous, next;
    private final long[] hostGeneration, hostRevision;
    private int head, pendingCount;
    private long generation;

    FitCountCache(PlacementContext context, PlacementPlanner planner, PlacementRequest[] catalogue,
            int cap, int maxCachedShapes) {
        if (cap <= 0 || maxCachedShapes <= 0) {
            throw new IllegalArgumentException("Fit-count cap and cached shape limit must be positive");
        }
        this.context = context;
        this.planner = planner;
        this.cap = cap;
        this.maxCachedShapes = maxCachedShapes;
        final var unique = new LinkedHashMap<Key, Sample>();
        for (final var request : catalogue) {
            final var key = key(context.model(), request);
            final var sample = unique.get(key);
            if (sample == null) {
                unique.put(key, new Sample(request, unique.size()));
            } else {
                sample.frequency++;
            }
        }
        samples = unique.values().toArray(Sample[]::new);
        pending = new Sample[samples.length];
        ready = new PriorityQueue<>(Math.max(1, samples.length),
                Comparator.comparingDouble(Sample::weightedTotal).thenComparingInt(sample -> sample.ordinal));
        final int size = context.model().hostCount + 1;
        previous = new int[size];
        next = new int[size];
        hostGeneration = new long[size];
        hostRevision = new long[size];
        head = size == 1 ? 0 : 1;
        for (int host = 1; host < size; host++) {
            previous[host] = host - 1;
            next[host] = host + 1 == size ? 0 : host + 1;
            hostRevision[host] = context.capacityRevision(host);
        }
    }

    void changed(int host) {
        final long revision = context.capacityRevision(host);
        if (hostRevision[host] == revision) {
            return;
        }
        hostRevision[host] = revision;
        hostGeneration[host] = ++generation;
        if (host == head) {
            return;
        }
        next[previous[host]] = next[host];
        if (next[host] != 0) {
            previous[next[host]] = previous[host];
        }
        previous[host] = 0;
        next[host] = head;
        previous[head] = host;
        head = host;
    }

    void beginOrder() {
        ready.clear();
        pendingCount = samples.length;
        for (int index = 0; index < samples.length; index++) {
            final var sample = samples[index];
            final long changes = generation - sample.generation;
            sample.bound = sample.generation < 0 || changes > sample.total / cap
                    ? 0 : (sample.total - changes * cap) / (double) sample.frequency;
            pending[index] = sample;
        }
        for (int index = pendingCount / 2 - 1; index >= 0; index--) {
            siftDown(index);
        }
    }

    Sample next() {
        while (pendingCount != 0 && (ready.isEmpty() || pending[0].bound <= ready.peek().weightedTotal())) {
            final var sample = pending[0];
            pending[0] = pending[--pendingCount];
            if (pendingCount != 0) {
                siftDown(0);
            }
            refresh(sample);
            ready.add(sample);
        }
        return ready.poll();
    }

    int count(Sample sample, int host) {
        return refresh(sample)[host];
    }

    long total(Sample sample) {
        refresh(sample);
        return sample.total;
    }

    int cachedShapes() {
        return rows.size();
    }

    private int[] refresh(Sample sample) {
        var counts = rows.get(sample);
        if (counts == null) {
            if (rows.size() == maxCachedShapes) {
                final var iterator = rows.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            counts = new int[hostGeneration.length];
            rows.put(sample, counts);
            sample.total = 0;
            sample.generation = -1;
        }
        for (int host = head; host != 0 && hostGeneration[host] > sample.generation; host = next[host]) {
            final int count = planner.fitCount(sample.request, host, cap);
            sample.total += count - counts[host];
            counts[host] = count;
        }
        sample.generation = generation;
        return counts;
    }

    private void siftDown(int index) {
        final var sample = pending[index];
        while (index < pendingCount / 2) {
            int child = index * 2 + 1;
            if (child + 1 < pendingCount && compare(pending[child + 1], pending[child]) < 0) {
                child++;
            }
            if (compare(sample, pending[child]) <= 0) {
                break;
            }
            pending[index] = pending[child];
            index = child;
        }
        pending[index] = sample;
    }

    private static int compare(Sample first, Sample second) {
        final int comparison = Double.compare(first.bound, second.bound);
        return comparison == 0 ? Integer.compare(first.ordinal, second.ordinal) : comparison;
    }

    private static Key key(PlacementModel model, PlacementRequest request) {
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
                values[offset++] = allocations.type[value];
                values[offset++] = Double.doubleToLongBits(allocations.amount[value]);
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

    static final class Sample {
        final PlacementRequest request;
        final int ordinal;
        int frequency = 1;
        long total, generation = -1;
        double bound;

        Sample(PlacementRequest request, int ordinal) {
            this.request = request;
            this.ordinal = ordinal;
        }

        double weightedTotal() {
            return total / (double) frequency;
        }
    }

    private record Key(long[] values) {
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
