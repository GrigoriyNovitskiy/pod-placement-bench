package one.cloud.benchmark.runner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.SplittableRandom;

import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation.COLOCATE_VALUE;

final class WorkloadInflation {
    private WorkloadInflation() {
    }

    static BenchmarkExperiment.Inflation run(Engine source, Request[] templates, BenchmarkExperiment.FleetCapacity capacity,
            int operation, int attempts, long seed) throws Exception {
        final var model = source.model();
        final var rules = model.rules;
        final var groupSizes = new HashMap<Integer, Integer>();
        int maxGroup = 0;
        for (final int group : rules.group) {
            maxGroup = Math.max(maxGroup, group);
        }
        for (int resource = model.hostCount + 1; resource < model.alloc.length; resource++) {
            final int profile = model.constraints[resource];
            if (rules.group[profile] != 0 && rules.kind[profile] > 3) {
                groupSizes.merge(rules.group[profile], 1, Integer::sum);
            }
        }
        for (int link = 0; link < rules.linkTarget.length; link++) {
            if (rules.linkKind[link] != COLOCATE_VALUE) {
                maxGroup = Math.max(maxGroup, rules.linkTarget[link]);
            }
        }
        final var eligible = new ArrayList<Request>();
        for (final var template : templates) {
            if (closed(model, template, groupSizes)) {
                eligible.add(template);
            }
        }
        if (eligible.isEmpty()) {
            return new BenchmarkExperiment.Inflation(operation, "no-eligible-templates", attempts, 0,
                    templates.length, null, 0, 0, 0, null, 0, Map.of(), null);
        }

        final var virtual = new ArrayList<ClusterSpec.VirtualResource>();
        final var offered = new Request[attempts];
        final var rng = new SplittableRandom(seed);
        final long maxMembers = Runtime.getRuntime().maxMemory() / 1024;
        long checksum = 0xcbf29ce484222325L;
        for (int attempt = 0; attempt < attempts; attempt++) {
            final var template = eligible.get(rng.nextInt(eligible.size()));
            if ((long) virtual.size() + template.size() > maxMembers) {
                throw new IllegalArgumentException("Inflation templates exceed the heap budget; reduce inflationAttempts");
            }
            final var members = new HashMap<Integer, Integer>();
            final var groups = new HashMap<Integer, Integer>();
            final var ids = new int[template.size()];
            checksum = mix(checksum, template.size());
            for (int index = 0; index < template.size(); index++) {
                final int resource = template.resource(index);
                ids[index] = Math.addExact(model.alloc.length, Math.addExact(virtual.size(), index));
                members.put(resource, ids[index]);
                checksum = mix(checksum, resource);
                final int group = rules.group[model.constraints[resource]];
                if (group != 0 && !groups.containsKey(group)) {
                    maxGroup = Math.incrementExact(maxGroup);
                    groups.put(group, maxGroup);
                }
            }
            for (int index = 0; index < template.size(); index++) {
                final int resource = template.resource(index);
                final int profile = model.constraints[resource];
                final int first = rules.linkOffset[profile];
                final var targets = new int[rules.linkOffset[profile + 1] - first];
                for (int link = 0; link < targets.length; link++) {
                    targets[link] = (rules.linkKind[first + link] == COLOCATE_VALUE ? members : groups)
                            .get(rules.linkTarget[first + link]);
                }
                virtual.add(new ClusterSpec.VirtualResource(resource, groups.getOrDefault(rules.group[profile], 0), targets));
            }
            offered[attempt] = new Request(ids);
        }

        final var extended = model.withVirtualResources(virtual.toArray(ClusterSpec.VirtualResource[]::new));
        final var trial = source.trial(extended).state();
        int admitted = 0;
        int afterDecline = 0;
        Integer firstDecline = null;
        final var admittedLogical = new double[model.dimensionCount + 1];
        try (final var policy = new SchedulingAlgorithm(trial, seed) {
            private final GreedyPlanner planner = new GreedyPlanner(context);

            @Override
            public void schedule(Request request) {
                for (int host = 1; host <= model.hostCount; host++) {
                    for (final int numa : planner.numaNodes(host)) {
                        final var plans = planner.plan(request, host, numa);
                        if (plans != null) {
                            context.place(request, plans);
                            return;
                        }
                    }
                }
            }
        }) {
            for (int index = 0; index < offered.length; index++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Benchmark interrupted");
                }
                final var request = offered[index];
                trial.schedule(policy, request);
                if (trial.placement(request.resource(0)) != null) {
                    admitted++;
                    if (firstDecline != null) {
                        afterDecline++;
                    }
                    BenchmarkExperiment.addDemand(extended, request, admittedLogical);
                } else if (firstDecline == null) {
                    firstDecline = index + 1;
                }
            }
        }
        return new BenchmarkExperiment.Inflation(operation, "complete", attempts, eligible.size(),
                templates.length - eligible.size(), Long.toUnsignedString(checksum, 16), attempts, admitted,
                attempts - admitted, firstDecline, afterDecline, BenchmarkExperiment.vector(admittedLogical),
                capacity.sample(trial));
    }

    private static boolean closed(ClusterSpec model, Request template, Map<Integer, Integer> groupSizes) {
        final var rules = model.rules;
        final var members = new HashSet<Integer>();
        final var groups = new HashMap<Integer, Integer>();
        for (int index = 0; index < template.size(); index++) {
            final int resource = template.resource(index);
            final int profile = model.constraints[resource];
            if (rules.kind[profile] != POD_VALUE && rules.kind[profile] != SHARD_VALUE) {
                return false;
            }
            members.add(resource);
            if (rules.group[profile] != 0) {
                groups.merge(rules.group[profile], 1, Integer::sum);
            }
        }
        for (final int resource : members) {
            final int profile = model.constraints[resource];
            for (int link = rules.linkOffset[profile]; link < rules.linkOffset[profile + 1]; link++) {
                final int target = rules.linkTarget[link];
                if (rules.linkKind[link] == COLOCATE_VALUE ? !members.contains(target)
                        : !groups.containsKey(target) || !groups.get(target).equals(groupSizes.get(target))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long mix(long hash, int value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
