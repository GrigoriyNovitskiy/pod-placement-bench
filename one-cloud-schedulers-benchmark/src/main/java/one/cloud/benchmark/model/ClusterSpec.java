package one.cloud.benchmark.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.Dataset.Allocs;
import one.cloud.benchmark.dataset.Dataset.CandidateSets;
import one.cloud.benchmark.dataset.Dataset.Rules;

/** Shared static tables. Treat their arrays as read-only. */
public final class ClusterSpec {
    private final ClusterSpec base;
    public enum GpuMode {
        AGGREGATE, DEVICES_20
    }

    public final long id;
    public final int hostCount;
    public final int dimensionCount;
    public final long extentBytes;
    public final int[] alloc;
    public final int[] constraints;
    public final Allocs allocations;
    public final Rules rules;
    public final CandidateSets candidates;
    public final GpuMode gpuMode;
    public final GpuTopology gpus;

    private ClusterSpec(Dataset dataset, GpuMode gpuMode) {
        Objects.requireNonNull(dataset);
        base = null;
        this.gpuMode = Objects.requireNonNull(gpuMode);
        id = dataset.id;
        hostCount = dataset.hostCount;
        dimensionCount = dataset.dimensionCount;
        extentBytes = dataset.extentBytes;
        alloc = dataset.alloc;
        constraints = dataset.constraints;
        allocations = dataset.allocations;
        rules = dataset.rules;
        candidates = dataset.candidates;
        gpus = gpuMode == GpuMode.DEVICES_20 ? new GpuTopology(this) : null;
    }

    private ClusterSpec(ClusterSpec source, int[] alloc, int[] constraints, Rules rules) {
        base = source;
        id = source.id;
        hostCount = source.hostCount;
        dimensionCount = source.dimensionCount;
        extentBytes = source.extentBytes;
        this.alloc = alloc;
        this.constraints = constraints;
        allocations = source.allocations;
        this.rules = rules;
        candidates = source.candidates;
        gpuMode = source.gpuMode;
        gpus = gpuMode == GpuMode.DEVICES_20 ? new GpuTopology(this) : null;
    }

    public record VirtualResource(int sourceResource, int group, int[] linkTargets) {
        public VirtualResource {
            linkTargets = Objects.requireNonNull(linkTargets).clone();
        }
    }

    public ClusterSpec withVirtualResources(VirtualResource[] resources) {
        Objects.requireNonNull(resources);
        final int count = Math.addExact(alloc.length, resources.length);
        if (8L * count > Runtime.getRuntime().maxMemory() / 4) {
            throw new IllegalArgumentException("Virtual resources exceed the configured heap budget");
        }
        int maxGroup = 0;
        for (final int group : rules.group) {
            maxGroup = Math.max(maxGroup, group);
        }
        for (int link = 0; link < rules.linkTarget.length; link++) {
            if (rules.linkKind[link] != 0) {
                maxGroup = Math.max(maxGroup, rules.linkTarget[link]);
            }
        }
        final int lastGroup = Math.addExact(maxGroup, resources.length);
        final var profiles = new int[resources.length];
        final var groups = new int[resources.length];
        final var targets = new int[resources.length][];
        final var origins = new HashMap<Integer, Integer>();
        for (int index = 0; index < resources.length; index++) {
            final var resource = Objects.requireNonNull(resources[index]);
            if (resource.sourceResource <= 0 || resource.sourceResource >= alloc.length) {
                throw new IllegalArgumentException("Unknown virtual source resource");
            }
            final int profile = constraints[resource.sourceResource];
            if (rules.kind[profile] != 4 && rules.kind[profile] != 5) {
                throw new IllegalArgumentException("Virtual resources must copy pods or shards");
            }
            final int originalGroup = rules.group[profile];
            if (originalGroup == 0 ? resource.group != 0 : resource.group <= maxGroup || resource.group > lastGroup) {
                throw new IllegalArgumentException("Virtual groups must be fresh and bounded");
            }
            if (resource.group != 0) {
                final var previous = origins.putIfAbsent(resource.group, originalGroup);
                if (previous != null && previous != originalGroup) {
                    throw new IllegalArgumentException("Virtual group merges distinct source groups");
                }
            }
            if (resource.linkTargets.length != rules.linkOffset[profile + 1] - rules.linkOffset[profile]) {
                throw new IllegalArgumentException("Virtual link targets must match the source profile");
            }
            for (int link = 0; link < resource.linkTargets.length; link++) {
                final int target = resource.linkTargets[link];
                final boolean colocate = rules.linkKind[rules.linkOffset[profile] + link] == 0;
                if (target <= 0 || (colocate ? target >= count : target > lastGroup)) {
                    throw new IllegalArgumentException("Invalid virtual link target");
                }
            }
            profiles[index] = profile;
            groups[index] = resource.group;
            targets[index] = resource.linkTargets;
        }
        final var virtualRules = rules.append(profiles, groups, targets);
        final var virtualAlloc = Arrays.copyOf(alloc, count);
        final var virtualConstraints = Arrays.copyOf(constraints, count);
        for (int index = 0; index < resources.length; index++) {
            virtualAlloc[alloc.length + index] = alloc[resources[index].sourceResource];
            virtualConstraints[alloc.length + index] = rules.kind.length + index;
        }
        return new ClusterSpec(this, virtualAlloc, virtualConstraints, virtualRules);
    }

    public boolean extendsModel(ClusterSpec source) {
        for (var current = this; current != null; current = current.base) {
            if (current == source) {
                return true;
            }
        }
        return false;
    }

    public static ClusterSpec from(Dataset dataset) {
        return from(dataset, GpuMode.DEVICES_20);
    }

    public static ClusterSpec from(Dataset dataset, GpuMode gpuMode) {
        return new ClusterSpec(dataset, gpuMode);
    }
}
