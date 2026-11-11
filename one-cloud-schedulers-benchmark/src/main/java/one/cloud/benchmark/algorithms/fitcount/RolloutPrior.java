package one.cloud.benchmark.algorithms.fitcount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import one.cloud.benchmark.dataset.RolloutPriorProtos.Bundle;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Prior;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

public final class RolloutPrior {
    public static final String VERSION = "rollout-prior-v1";

    private final ClusterSpec source, model;
    private final Request[][][] instances;
    private final long[] cumulativeWeights;
    private final int[] bundleSizes;
    private final int horizon;
    private final String digest, unsupportedReason;

    private RolloutPrior(Prior prior, ClusterSpec source, int horizon) throws IOException {
        if (horizon <= 0) {
            throw new IllegalArgumentException("Rollout horizon must be positive");
        }
        if (!prior.getVersion().equals(VERSION) || prior.getDatasetId() != source.id || prior.getBundlesCount() == 0) {
            throw invalid("Wrong version, dataset identity or empty bundle list");
        }
        final var actualDigest = canonicalDigest(prior);
        if (!MessageDigest.isEqual(prior.getSha256().toByteArray(), actualDigest)) {
            throw invalid("Prior digest does not match canonical content");
        }
        this.source = source;
        this.horizon = horizon;
        digest = HexFormat.of().formatHex(actualDigest);
        cumulativeWeights = new long[prior.getBundlesCount()];
        bundleSizes = new int[cumulativeWeights.length];
        final var groups = new HashMap<Integer, Integer>();
        int maxGroup = 0;
        for (int resource = 1; resource < source.alloc.length; resource++) {
            final int profile = source.constraints[resource];
            final int group = source.rules.group[profile];
            maxGroup = Math.max(maxGroup, group);
            final int kind = source.rules.kind[profile];
            if (group != 0 && (kind < 1 || kind > 3)) {
                groups.merge(group, 1, Math::addExact);
            }
        }
        for (int link = 0; link < source.rules.linkTarget.length; link++) {
            if (source.rules.linkKind[link] != 0) {
                maxGroup = Math.max(maxGroup, source.rules.linkTarget[link]);
            }
        }
        String unsupported = "";
        int previous = 0;
        long weight = 0, members = 0, groupCopies = 0, workspace = 0;
        for (int index = 0; index < prior.getBundlesCount(); index++) {
            final var bundle = prior.getBundles(index);
            if (bundle.getId() <= previous || bundle.getWeight() <= 0 || bundle.getUnitsCount() == 0) {
                throw invalid("Bundle IDs must increase and weights and bundle sizes must be positive");
            }
            previous = bundle.getId();
            try {
                weight = Math.addExact(weight, bundle.getWeight());
            } catch (ArithmeticException failure) {
                throw invalid("Bundle weight sum overflows", failure);
            }
            cumulativeWeights[index] = weight;
            bundleSizes[index] = bundle.getUnitsCount();
            final String reason = validate(bundle, source, groups);
            if (unsupported.isEmpty()) {
                unsupported = reason;
            }
            if (bundle.getUnitsCount() > horizon && unsupported.isEmpty()) {
                unsupported = "bundle-exceeds-horizon";
            }
            for (final var unit : bundle.getUnitsList()) {
                for (final var member : unit.getMembersList()) {
                    members++;
                    final int profile = source.constraints[member.getSourceResource()];
                    final long cost = 256L + 16L * (source.rules.linkOffset[profile + 1] - source.rules.linkOffset[profile])
                            + 128L * (source.rules.diskOffset[profile + 1] - source.rules.diskOffset[profile]);
                    final long budget = Runtime.getRuntime().maxMemory() / (4L * horizon);
                    workspace = workspace > budget - cost ? budget + 1 : workspace + cost;
                }
            }
            groupCopies += bundle.getGroupsCount();
            final long cost = 64L * bundle.getUnitsCount();
            final long budget = Runtime.getRuntime().maxMemory() / (4L * horizon);
            workspace = workspace > budget - cost ? budget + 1 : workspace + cost;
        }
        if (members > (Integer.MAX_VALUE - 8L - source.alloc.length) / horizon
                || groupCopies > (Integer.MAX_VALUE - 8L - maxGroup) / horizon
                || workspace > Runtime.getRuntime().maxMemory() / (4L * horizon)) {
            if (unsupported.isEmpty()) {
                unsupported = "prior-bank-memory";
            }
        }
        unsupportedReason = unsupported;
        if (!unsupported.isEmpty()) {
            model = source;
            instances = new Request[0][][];
            return;
        }
        final var resources = new ArrayList<ClusterSpec.VirtualResource>((int) (members * horizon));
        instances = new Request[prior.getBundlesCount()][horizon][];
        int nextGroup = maxGroup;
        for (int bundleIndex = 0; bundleIndex < prior.getBundlesCount(); bundleIndex++) {
            final var bundle = prior.getBundles(bundleIndex);
            for (int instance = 0; instance < horizon; instance++) {
                final var reboundGroups = new HashMap<Integer, Integer>();
                for (final var group : bundle.getGroupsList()) {
                    reboundGroups.put(group.getSourceGroup(), ++nextGroup);
                }
                final var reboundMembers = new HashMap<Integer, Integer>();
                int nextResource = source.alloc.length + resources.size();
                for (final var unit : bundle.getUnitsList()) {
                    for (final var member : unit.getMembersList()) {
                        reboundMembers.put(member.getSourceResource(), nextResource++);
                    }
                }
                final var units = new Request[bundle.getUnitsCount()];
                for (int unitIndex = 0; unitIndex < units.length; unitIndex++) {
                    final var unit = bundle.getUnits(unitIndex);
                    final var ids = new int[unit.getMembersCount()];
                    for (int memberIndex = 0; memberIndex < ids.length; memberIndex++) {
                        final int resource = unit.getMembers(memberIndex).getSourceResource();
                        ids[memberIndex] = reboundMembers.get(resource);
                        final int profile = source.constraints[resource];
                        final int from = source.rules.linkOffset[profile];
                        final var targets = new int[source.rules.linkOffset[profile + 1] - from];
                        for (int link = 0; link < targets.length; link++) {
                            final int target = source.rules.linkTarget[from + link];
                            targets[link] = (source.rules.linkKind[from + link] == 0 ? reboundMembers : reboundGroups)
                                    .getOrDefault(target, target);
                        }
                        final int group = source.rules.group[profile];
                        resources.add(new ClusterSpec.VirtualResource(resource,
                                group == 0 ? 0 : reboundGroups.get(group), targets));
                    }
                    units[unitIndex] = new Request(ids);
                }
                instances[bundleIndex][instance] = units;
            }
        }
        model = source.withVirtualResources(resources.toArray(ClusterSpec.VirtualResource[]::new));
    }

    public static RolloutPrior load(Path path, ClusterSpec model, int horizon) throws IOException {
        if (Files.size(path) > Runtime.getRuntime().maxMemory() / 8) {
            throw invalid("Prior file exceeds the configured heap budget");
        }
        try (final var input = Files.newInputStream(path)) {
            return new RolloutPrior(Prior.parseFrom(input), model, horizon);
        }
    }

    public static byte[] canonicalDigest(Prior prior) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(prior.toBuilder().clearSha256().build().toByteArray());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public String digest() {
        return digest;
    }

    public String unsupportedReason() {
        return unsupportedReason;
    }

    ClusterSpec source() {
        return source;
    }

    ClusterSpec model() {
        return model;
    }

    int horizon() {
        return horizon;
    }

    Request[] sample(long seed) {
        final var random = new SplittableRandom(seed);
        final var uses = new int[instances.length];
        final var path = new Request[horizon];
        int size = 0;
        for (int draw = 0; draw < horizon && size < horizon; draw++) {
            final long selected = random.nextLong(cumulativeWeights[cumulativeWeights.length - 1]);
            int low = 0, high = cumulativeWeights.length;
            while (low < high) {
                final int middle = low + (high - low) / 2;
                if (selected < cumulativeWeights[middle]) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            if (bundleSizes[low] > horizon - size) {
                break;
            }
            for (final var request : instances[low][uses[low]++]) {
                path[size++] = request;
            }
        }
        return Arrays.copyOf(path, size);
    }

    private static String validate(Bundle bundle, ClusterSpec model, HashMap<Integer, Integer> groupSizes) throws IOException {
        final var internalGroups = new HashSet<Integer>();
        final var externalResources = new HashSet<Integer>();
        final var externalGroups = new HashSet<Integer>();
        increasing(bundle.getExternalResourcesList(), externalResources, "external resources");
        increasing(bundle.getExternalGroupsList(), externalGroups, "external groups");
        for (final int resource : externalResources) {
            checkResource(model, resource);
        }
        for (final int group : externalGroups) {
            if (!groupSizes.containsKey(group)) {
                throw invalid("Unknown external group");
            }
        }
        String unsupported = "";
        int previous = 0;
        for (final var group : bundle.getGroupsList()) {
            final int id = group.getSourceGroup();
            if (id <= previous || group.getCardinality() <= 0 || !groupSizes.containsKey(id)) {
                throw invalid("Internal group IDs must increase and have a positive cardinality");
            }
            previous = id;
            internalGroups.add(id);
            if (externalGroups.contains(id)) {
                throw invalid("A group cannot be both internal and external");
            }
            if (group.getCardinality() != groupSizes.get(id)) {
                throw invalid("Declared group cardinality differs from the source model");
            }
        }
        final var owner = new HashMap<Integer, Integer>();
        final var actualGroups = new HashMap<Integer, Integer>();
        final var lastOwner = new HashMap<Integer, Integer>();
        previous = 0;
        for (int unitIndex = 0; unitIndex < bundle.getUnitsCount(); unitIndex++) {
            final var unit = bundle.getUnits(unitIndex);
            if (unit.getId() <= previous || unit.getMembersCount() == 0) {
                throw invalid("Unit IDs must increase and units must be nonempty");
            }
            previous = unit.getId();
            int previousResource = 0;
            for (final var member : unit.getMembersList()) {
                final int resource = member.getSourceResource();
                checkResource(model, resource);
                if (resource <= previousResource || owner.put(resource, unitIndex) != null || externalResources.contains(resource)) {
                    throw invalid("Template members must be distinct and ordered, without external aliases");
                }
                previousResource = resource;
                final int profile = model.constraints[resource];
                if (member.getCandidates() != model.rules.candidates[profile]
                        || member.getPinnedHost() != model.rules.pinnedHost[profile]) {
                    throw invalid("Member masks and pins must declare the original references");
                }
                final int group = model.rules.group[profile];
                if (group != 0) {
                    if (!internalGroups.contains(group)) {
                        throw invalid("Every member group needs an explicit internal binding");
                    }
                    actualGroups.merge(group, 1, Math::addExact);
                    lastOwner.put(group, unitIndex);
                }
            }
        }
        for (final int group : internalGroups) {
            if (actualGroups.getOrDefault(group, 0).intValue() != groupSizes.get(group).intValue()) {
                unsupported = "incomplete-source-group";
            }
        }
        final var usedExternalResources = new HashSet<Integer>();
        final var usedExternalGroups = new HashSet<Integer>();
        for (int unit = 0; unit < bundle.getUnitsCount(); unit++) {
            for (final var member : bundle.getUnits(unit).getMembersList()) {
                final int profile = model.constraints[member.getSourceResource()];
                for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
                    final int target = model.rules.linkTarget[link];
                    if (model.rules.linkKind[link] == 0) {
                        final var targetOwner = owner.get(target);
                        if (targetOwner == null) {
                            if (!externalResources.contains(target)) {
                                throw invalid("Undeclared external resource reference");
                            }
                            usedExternalResources.add(target);
                        } else if (targetOwner > unit) {
                            unsupported = "unordered-dependency-closure";
                        }
                    } else if (internalGroups.contains(target)) {
                        if (!lastOwner.containsKey(target) || lastOwner.get(target) > unit) {
                            unsupported = "unordered-dependency-closure";
                        }
                    } else {
                        if (!externalGroups.contains(target)) {
                            throw invalid("Undeclared external group reference");
                        }
                        usedExternalGroups.add(target);
                    }
                }
            }
        }
        if (!usedExternalResources.equals(externalResources) || !usedExternalGroups.equals(externalGroups)) {
            throw invalid("Unused external references");
        }
        return unsupported;
    }

    private static void increasing(List<Integer> values, HashSet<Integer> target, String what) throws IOException {
        int previous = 0;
        for (final int value : values) {
            if (value <= previous) {
                throw invalid("IDs must increase in " + what);
            }
            previous = value;
            target.add(value);
        }
    }

    private static void checkResource(ClusterSpec model, int resource) throws IOException {
        if (resource <= 0 || resource >= model.alloc.length) {
            throw invalid("Unknown source resource");
        }
        final int kind = model.rules.kind[model.constraints[resource]];
        if (kind != 4 && kind != 5) {
            throw invalid("Source templates and external resources must be pods or shards");
        }
    }

    private static IOException invalid(String message) {
        return new IOException("Invalid rollout prior: " + message);
    }

    private static IOException invalid(String message, Exception cause) {
        return new IOException("Invalid rollout prior: " + message, cause);
    }
}
