package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;

import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Request;

final class FitCountCache {
    private final SchedulingContext context;
    private final GreedyPlanner planner;
    private final int cap;
    private final int maxCachedShapes;
    private final Sample[] samples;
    private final Sample[] pending;
    private final Map<String, String> parameters;
    private final PriorityQueue<Sample> ready;
    private final LinkedHashMap<Sample, int[]> rows = new LinkedHashMap<>(16, .75f, true);
    private final int[] previous, next;
    private final long[] hostGeneration, hostRevision;
    private int head, pendingCount;
    private long generation;
    private double maxTotalFit = Double.MAX_VALUE;
    private MaskedFitCountOrder maskedOrder;

    FitCountCache(SchedulingContext context, GreedyPlanner planner, Request[] catalogue,
            int cap, int maxCachedShapes) {
        this(context, planner, catalogue, cap, maxCachedShapes, null);
    }

    FitCountCache(SchedulingContext context, GreedyPlanner planner, Request[] catalogue,
            int cap, int maxCachedShapes, MaskedFitCountOrder.Compiler compiler) {
        this(context, planner, Catalogue.sampled(catalogue), cap, maxCachedShapes, compiler);
    }

    FitCountCache(SchedulingContext context, GreedyPlanner planner, Catalogue catalogue,
            int cap, int maxCachedShapes, MaskedFitCountOrder.Compiler compiler) {
        if (cap <= 0 || maxCachedShapes <= 0) {
            throw new IllegalArgumentException("Fit-count cap and cached shape limit must be positive");
        }
        this.context = context;
        this.planner = planner;
        this.cap = cap;
        this.maxCachedShapes = maxCachedShapes;
        final var unique = new LinkedHashMap<Catalogue.Key, Sample>();
        for (int occurrence = 0; occurrence < catalogue.size(); occurrence++) {
            final var request = catalogue.request(occurrence);
            final var key = Catalogue.key(context.model(), request);
            var sample = unique.get(key);
            if (sample == null) {
                sample = new Sample(request, unique.size());
                sample.frequency = catalogue.frequency(occurrence);
                unique.put(key, sample);
            } else if (!catalogue.hasFullFrequencies()) {
                sample.frequency++;
            } else if (sample.frequency != catalogue.frequency(occurrence)) {
                throw new IllegalArgumentException("Physical shape has inconsistent catalogue frequencies");
            }
            if (compiler != null) {
                compiler.add(request, sample, occurrence);
            }
        }
        long mass = 0, checksum = Catalogue.HASH_START;
        for (final var family : unique.entrySet()) {
            mass += family.getValue().frequency;
            checksum = Catalogue.checksum(checksum, family.getKey(), family.getValue().frequency);
        }
        parameters = catalogue.parameters(unique.size(), mass, checksum);
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

    void attach(MaskedFitCountOrder order) {
        maskedOrder = order;
    }

    Map<String, String> parameters() {
        return parameters;
    }

    long generation() {
        return generation;
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
        beginOrder(Double.MAX_VALUE);
    }

    void beginOrder(double maxTotalFit) {
        this.maxTotalFit = maxTotalFit;
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
        while (pendingCount != 0 && pending[0].bound <= maxTotalFit
                && (ready.isEmpty() || pending[0].bound <= ready.peek().weightedTotal())) {
            final var sample = pending[0];
            pending[0] = pending[--pendingCount];
            if (pendingCount != 0) {
                siftDown(0);
            }
            refresh(sample);
            ready.add(sample);
        }
        final var sample = ready.poll();
        return sample != null && sample.weightedTotal() <= maxTotalFit ? sample : null;
    }

    int count(Sample sample, int host) {
        return refresh(sample)[host];
    }

    Sample[] samples() {
        return samples;
    }

    int[] counts(Sample sample) {
        return refresh(sample);
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
        if (counts != null && sample.generation == generation) {
            return counts;
        }
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
            if (maskedOrder != null) {
                maskedOrder.rebuilding(sample);
            }
        }
        for (int host = head; host != 0 && hostGeneration[host] > sample.generation; host = next[host]) {
            final int count = planner.fitCount(sample.request, host, cap);
            sample.total += count - counts[host];
            if (maskedOrder != null) {
                maskedOrder.hostChanged(sample, host, counts[host], count);
            }
            counts[host] = count;
        }
        sample.generation = generation;
        if (maskedOrder != null) {
            maskedOrder.refreshed(sample, generation);
        }
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

    static final class Sample {
        final Request request;
        final int ordinal;
        int frequency = 1;
        long total, generation = -1;
        double bound;

        Sample(Request request, int ordinal) {
            this.request = request;
            this.ordinal = ordinal;
        }

        double weightedTotal() {
            return total / (double) frequency;
        }
    }


}
