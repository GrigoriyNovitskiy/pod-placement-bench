package one.cloud.benchmark.runner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation.COLOCATE_VALUE;

/** Cold, deterministic normalization of complete synthetic traces into atomic request lifetimes. */
public final class Scenario {
    public static final String NORMALIZATION_VERSION = "joint-phases-v1";
    public static final String BURST_VERSION = "service-bursts-v1";

    public final ClusterSpec model;
    public final Request[] requests;
    public final int[] events;
    public final int[] checkpoints;
    public final int rawOperations;
    public final int partialChurn;
    public final int expandedRestarts;
    public final String digest;
    public final String sourceOpsDigest;
    public final int maxBurstSize;
    private final int[] windowEnds;

    private Scenario(Compiler compiler) {
        maxBurstSize = 1;
        windowEnds = new int[0];
        model = ClusterSpec.from(compiler.data, compiler.gpuMode);
        requests = compiler.requests;
        events = compiler.events.values();
        checkpoints = compiler.checkpoints.values();
        rawOperations = compiler.data.operations.resource.length;
        partialChurn = compiler.partialChurn;
        expandedRestarts = compiler.expandedRestarts;
        sourceOpsDigest = sourceDigest(compiler.data);
        final var hash = hash();
        hash.update(NORMALIZATION_VERSION.getBytes(StandardCharsets.UTF_8));
        if (compiler.gpuMode != ClusterSpec.GpuMode.AGGREGATE) {
            hash.update(compiler.gpuMode.name().getBytes(StandardCharsets.UTF_8));
        }
        hash.update(HexFormat.of().parseHex(sourceOpsDigest));
        final var buffer = ByteBuffer.allocate(Long.BYTES);
        update(hash, buffer, model.id);
        update(hash, buffer, requests.length);
        for (final var request : requests) {
            update(hash, buffer, request.size());
            for (int member = 0; member < request.size(); member++) {
                update(hash, buffer, request.resource(member));
            }
        }
        update(hash, buffer, events.length);
        for (final int event : events) {
            update(hash, buffer, event);
        }
        update(hash, buffer, checkpoints.length);
        for (final int checkpoint : checkpoints) {
            update(hash, buffer, checkpoint);
        }
        digest = HexFormat.of().formatHex(hash.digest());
    }

    private Scenario(Scenario source, int size) {
        model = source.model;
        requests = source.requests;
        events = source.events;
        checkpoints = source.checkpoints;
        rawOperations = source.rawOperations;
        partialChurn = source.partialChurn;
        expandedRestarts = source.expandedRestarts;
        sourceOpsDigest = source.sourceOpsDigest;
        maxBurstSize = size;
        windowEnds = new int[events.length];
        final var hash = hash();
        hash.update(HexFormat.of().parseHex(source.digest));
        hash.update(BURST_VERSION.getBytes(StandardCharsets.UTF_8));
        final var buffer = ByteBuffer.allocate(Long.BYTES);
        update(hash, buffer, size);
        for (int start = 0; start < events.length;) {
            int end = start + 1;
            final int group = events[start] > 0 ? service(requests[events[start] - 1]) : 0;
            while (group != 0 && end < events.length && end - start < size && events[end] > 0
                    && Arrays.binarySearch(checkpoints, end) < 0 && service(requests[events[end] - 1]) == group) {
                end++;
            }
            windowEnds[start] = end;
            update(hash, buffer, end);
            start = end;
        }
        digest = HexFormat.of().formatHex(hash.digest());
    }

    public Scenario withServiceBursts(int size) {
        if (size < 1 || size > 100 || maxBurstSize != 1) {
            throw new IllegalArgumentException("Service bursts require an unwindowed scenario and size 1..100");
        }
        return size == 1 ? this : new Scenario(this, size);
    }

    public int windowEnd(int start) {
        if (start < 0 || start >= events.length || windowEnds.length != 0 && windowEnds[start] == 0) {
            throw new IllegalArgumentException("Not an announcement boundary");
        }
        return windowEnds.length == 0 ? start + 1 : windowEnds[start];
    }

    private int service(Request request) {
        int group = 0;
        int pods = 0;
        for (int index = 0; index < request.size(); index++) {
            final int profile = model.constraints[request.resource(index)];
            if (model.rules.kind[profile] == POD_VALUE) {
                pods++;
                group = model.rules.group[profile];
            }
        }
        return pods == 1 ? group : 0;
    }

    public static Scenario from(Dataset dataset) {
        return from(dataset, ClusterSpec.GpuMode.DEVICES_20);
    }

    public static Scenario from(Dataset dataset, ClusterSpec.GpuMode gpuMode) {
        return new Compiler(dataset, Objects.requireNonNull(gpuMode)).compile();
    }

    private static String sourceDigest(Dataset dataset) {
        final var hash = hash();
        final var buffer = ByteBuffer.allocate(Long.BYTES);
        update(hash, buffer, dataset.id);
        update(hash, buffer, dataset.operations.resource.length);
        for (int index = 0; index < dataset.operations.resource.length; index++) {
            update(hash, buffer, dataset.operations.time[index]);
            update(hash, buffer, dataset.operations.resource[index]);
            update(hash, buffer, dataset.operations.generation[index]);
            hash.update((byte) (dataset.operations.create[index] ? 1 : 0));
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest hash() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void update(MessageDigest hash, ByteBuffer buffer, long value) {
        buffer.clear();
        buffer.putLong(value);
        hash.update(buffer.array());
    }

    private static final class Compiler {
        private final Dataset data;
        private final ClusterSpec.GpuMode gpuMode;
        private final Dataset.Operations operations;
        private final int resourceCount;
        private final Ints phases = new Ints();
        private final Ints events = new Ints();
        private final Ints checkpoints = new Ints();
        private Request[] requests = new Request[0];
        private int[] units;
        private int[] order;
        private Group[] groups;
        private int[][] memberships;
        private int partialChurn;
        private int expandedRestarts;

        private Compiler(Dataset data, ClusterSpec.GpuMode gpuMode) {
            if (data == null || !data.operations.synthetic || data.operations.replayFromSnapshot) {
                throw new IllegalArgumentException("A synthetic empty-start operation stream is required");
            }
            this.data = data;
            this.gpuMode = gpuMode;
            operations = data.operations;
            resourceCount = data.alloc.length;
        }

        private Scenario compile() {
            if (operations.resource.length == 0) {
                return new Scenario(this);
            }
            final long workspace = 24L * resourceCount + 40L * operations.resource.length
                    + 16L * data.rules.linkTarget.length;
            if (workspace > Runtime.getRuntime().maxMemory() / 2) {
                throw new IllegalArgumentException("Scenario workspace exceeds half of the configured Java heap");
            }
            validate();
            buildRequests();
            buildGroups();
            sort();
            normalize();
            return new Scenario(this);
        }

        private void validate() {
            final var active = new boolean[resourceCount];
            final var generation = new int[resourceCount];
            int activeCount = 0;
            boolean initial = true;
            for (int index = 0; index < operations.resource.length; index++) {
                final int resource = operations.resource[index];
                if (resource <= 0 || resource >= resourceCount
                        || !workload(resource) || operations.time[index] < 0
                        || index > 0 && operations.time[index] < operations.time[index - 1]) {
                    throw invalid(index, "Invalid resource or operation order");
                }
                if (index == 0 && !operations.create[index]) {
                    throw invalid(index, "An empty-start trace must begin with creation");
                }
                if (index > 0 && operations.create[index] != operations.create[index - 1]) {
                    phases.add(index);
                    initial = false;
                }
                if (operations.create[index]) {
                    if (active[resource] || !initial && generation[resource] == 0
                            || (long) operations.generation[index] != (long) generation[resource] + 1) {
                        throw invalid(index, "Invalid creation lifetime or generation");
                    }
                    active[resource] = true;
                    activeCount++;
                    generation[resource] = operations.generation[index];
                } else {
                    if (!active[resource] || generation[resource] != operations.generation[index]) {
                        throw invalid(index, "Invalid deletion lifetime or generation");
                    }
                    active[resource] = false;
                    activeCount--;
                }
            }
            phases.add(operations.resource.length);
            if ((phases.size & 1) != 0 || activeCount != 0) {
                throw new IllegalArgumentException("The complete synthetic trace must finish with a full drain");
            }
            final var deleted = new int[resourceCount];
            for (int phase = 1; phase + 1 < phases.size; phase += 2) {
                final int start = phases.get(phase - 1);
                final int end = phases.get(phase);
                final int recreated = phases.get(phase + 1);
                if (end - start != recreated - end) {
                    throw invalid(start, "A churn phase must recreate the same resource set");
                }
                for (int index = start; index < end; index++) {
                    deleted[operations.resource[index]] = phase;
                }
                for (int index = end; index < recreated; index++) {
                    if (deleted[operations.resource[index]] != phase) {
                        throw invalid(index, "A churn phase must recreate the same resource set");
                    }
                }
            }
        }

        private void buildRequests() {
            final int initialEnd = phases.get(0);
            final var parent = new int[resourceCount];
            Arrays.fill(parent, -1);
            for (int index = 0; index < initialEnd; index++) {
                final int resource = operations.resource[index];
                parent[resource] = resource;
            }
            for (int index = 0; index < initialEnd; index++) {
                final int resource = operations.resource[index];
                final int profile = data.constraints[resource];
                for (int link = data.rules.linkOffset[profile]; link < data.rules.linkOffset[profile + 1]; link++) {
                    if (data.rules.linkKind[link] == COLOCATE_VALUE) {
                        final int target = data.rules.linkTarget[link];
                        if (target <= 0 || target >= resourceCount || parent[target] == -1) {
                            throw new IllegalArgumentException("Missing initial colocation target for resource " + resource);
                        }
                        final int first = root(parent, resource);
                        final int second = root(parent, target);
                        parent[Math.max(first, second)] = Math.min(first, second);
                    }
                }
            }
            units = new int[resourceCount];
            Arrays.fill(units, -1);
            final var rootUnit = new int[resourceCount];
            Arrays.fill(rootUnit, -1);
            final var counts = new int[initialEnd];
            int unitCount = 0;
            for (int index = 0; index < initialEnd; index++) {
                final int resource = operations.resource[index];
                final int root = root(parent, resource);
                if (rootUnit[root] == -1) {
                    rootUnit[root] = unitCount++;
                }
                final int unit = rootUnit[root];
                units[resource] = unit;
                counts[unit]++;
            }
            final var members = new int[unitCount][];
            for (int unit = 0; unit < unitCount; unit++) {
                members[unit] = new int[counts[unit]];
            }
            Arrays.fill(counts, 0);
            for (int resource = 1; resource < resourceCount; resource++) {
                if (units[resource] >= 0) {
                    final int unit = units[resource];
                    members[unit][counts[unit]++] = resource;
                }
            }
            requests = new Request[unitCount];
            for (int unit = 0; unit < unitCount; unit++) {
                requests[unit] = new Request(members[unit]);
            }
        }

        private void buildGroups() {
            final var referenced = new TreeMap<Integer, Group>();
            for (int resource = 1; resource < resourceCount; resource++) {
                if (units[resource] < 0) {
                    continue;
                }
                final int profile = data.constraints[resource];
                for (int link = data.rules.linkOffset[profile]; link < data.rules.linkOffset[profile + 1]; link++) {
                    if (data.rules.linkKind[link] != COLOCATE_VALUE) {
                        referenced.computeIfAbsent(data.rules.linkTarget[link], ignored -> new Group())
                                .consumerBuilder.add(units[resource]);
                    }
                }
            }
            for (int resource = 1; resource < resourceCount; resource++) {
                final int profile = data.constraints[resource];
                final int kind = data.rules.kind[profile];
                if (kind >= 1 && kind <= 3) {
                    continue;
                }
                final var group = referenced.get(data.rules.group[profile]);
                if (group != null) {
                    if (units[resource] < 0) {
                        throw new IllegalArgumentException("Missing initial group member " + resource);
                    }
                    group.memberBuilder.add(units[resource]);
                }
            }
            groups = referenced.values().toArray(Group[]::new);
            final var counts = new int[requests.length];
            for (final var group : groups) {
                group.members = group.memberBuilder.unique();
                group.consumers = group.consumerBuilder.unique();
                if (group.members.length == 0) {
                    throw new IllegalArgumentException("A referenced group has no workload members");
                }
                group.remaining = group.members.length;
                for (final int unit : group.members) {
                    group.xor ^= unit;
                    counts[unit]++;
                }
            }
            memberships = new int[requests.length][];
            for (int unit = 0; unit < requests.length; unit++) {
                memberships[unit] = new int[counts[unit]];
            }
            Arrays.fill(counts, 0);
            for (int group = 0; group < groups.length; group++) {
                for (final int unit : groups[group].members) {
                    memberships[unit][counts[unit]++] = group;
                }
            }
        }

        private void sort() {
            final var unmet = new int[requests.length];
            for (final var group : groups) {
                for (final int consumer : group.consumers) {
                    if (group.remaining != 1 || group.xor != consumer) {
                        unmet[consumer]++;
                    }
                }
            }
            final var ready = new PriorityQueue<Integer>();
            for (int unit = 0; unit < requests.length; unit++) {
                if (unmet[unit] == 0) {
                    ready.add(unit);
                }
            }
            final var processed = new boolean[requests.length];
            order = new int[requests.length];
            int next = 0;
            while (!ready.isEmpty()) {
                final int unit = ready.remove();
                processed[unit] = true;
                order[next++] = unit;
                for (final int index : memberships[unit]) {
                    final var group = groups[index];
                    group.remaining--;
                    group.xor ^= unit;
                    if (group.remaining == 1) {
                        final int self = group.xor;
                        if (Arrays.binarySearch(group.consumers, self) >= 0 && --unmet[self] == 0) {
                            ready.add(self);
                        }
                    } else if (group.remaining == 0) {
                        for (final int consumer : group.consumers) {
                            if (!processed[consumer] && --unmet[consumer] == 0) {
                                ready.add(consumer);
                            }
                        }
                    }
                }
            }
            if (next != requests.length) {
                throw new IllegalArgumentException("Joint colocation and group dependencies contain a cycle");
            }
        }

        private void normalize() {
            for (final int unit : order) {
                events.add(unit + 1);
            }
            checkpoints.add(events.size);
            final var seen = new int[requests.length];
            final var seenGroups = new int[groups.length];
            final var deletedMembers = new int[requests.length];
            final var queue = new int[requests.length];
            for (int phase = 1; phase < phases.size; phase += 2) {
                if (phase == phases.size - 1) {
                    for (int index = order.length - 1; index >= 0; index--) {
                        events.add(-order[index] - 1);
                    }
                    break;
                }
                int size = 0;
                for (int index = phases.get(phase - 1); index < phases.get(phase); index++) {
                    final int unit = units[operations.resource[index]];
                    deletedMembers[unit]++;
                    if (seen[unit] != phase) {
                        seen[unit] = phase;
                        queue[size++] = unit;
                    }
                }
                final int touched = size;
                for (int index = 0; index < touched; index++) {
                    final int unit = queue[index];
                    if (deletedMembers[unit] != requests[unit].size()) {
                        partialChurn++;
                    }
                    deletedMembers[unit] = 0;
                }
                for (int next = 0; next < size; next++) {
                    for (final int group : memberships[queue[next]]) {
                        if (seenGroups[group] != phase) {
                            seenGroups[group] = phase;
                            for (final int consumer : groups[group].consumers) {
                                if (seen[consumer] != phase) {
                                    seen[consumer] = phase;
                                    queue[size++] = consumer;
                                }
                            }
                        }
                    }
                }
                expandedRestarts = Math.addExact(expandedRestarts, size - touched);
                for (int index = order.length - 1; index >= 0; index--) {
                    if (seen[order[index]] == phase) {
                        events.add(-order[index] - 1);
                    }
                }
                for (final int unit : order) {
                    if (seen[unit] == phase) {
                        events.add(unit + 1);
                    }
                }
                checkpoints.add(events.size);
            }
        }

        private boolean workload(int resource) {
            final int kind = data.rules.kind[data.constraints[resource]];
            return kind == POD_VALUE || kind == SHARD_VALUE;
        }

        private IllegalArgumentException invalid(int operation, String message) {
            return new IllegalArgumentException(message + " at operation " + operation);
        }
    }

    private static int root(int[] parent, int resource) {
        while (parent[resource] != resource) {
            parent[resource] = parent[parent[resource]];
            resource = parent[resource];
        }
        return resource;
    }

    private static final class Group {
        private final Ints memberBuilder = new Ints();
        private final Ints consumerBuilder = new Ints();
        private int[] members;
        private int[] consumers;
        private int remaining;
        private int xor;
    }

    private static final class Ints {
        private int[] values = new int[8];
        private int size;

        private void add(int value) {
            final long limit = Math.min(Integer.MAX_VALUE - 8L, Runtime.getRuntime().maxMemory() / (8L * Integer.BYTES));
            if (size >= limit) {
                throw new IllegalArgumentException("Normalized scenario exceeds the configured Java heap budget");
            }
            if (size == values.length) {
                values = Arrays.copyOf(values, (int) Math.min(limit, 2L * size));
            }
            values[size++] = value;
        }

        private int get(int index) {
            return values[index];
        }

        private int[] values() {
            return Arrays.copyOf(values, size);
        }

        private int[] unique() {
            final var sorted = values();
            Arrays.sort(sorted);
            int next = 0;
            for (final int value : sorted) {
                if (next == 0 || value != sorted[next - 1]) {
                    sorted[next++] = value;
                }
            }
            return Arrays.copyOf(sorted, next);
        }
    }
}
