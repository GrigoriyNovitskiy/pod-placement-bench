package one.cloud.benchmark.algorithms.ilp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

final class RelocationCandidates {
    static final int SOURCE_LIMIT = 6;
    static final int VICTIM_LIMIT = 24;
    static final int DESTINATION_LIMIT = 8;
    static final int NUMA_LIMIT = 2;
    static final int CPU = ResourceType.VCORES_VALUE;
    static final int RAM = ResourceType.MEM_MIB_VALUE;

    private final Engine engine;
    private final ClusterSpec model;

    RelocationCandidates(Engine engine) {
        this.engine = engine;
        model = engine.model();
    }

    List<Integer> rankedHosts() {
        final var hosts = new ArrayList<Integer>();
        final var freeCpu = new double[model.hostCount + 1];
        final var freeRam = new double[freeCpu.length];
        for (int host = 1; host <= model.hostCount; host++) {
            if ((model.rules.flags[model.constraints[host]] & 1) == 0) {
                hosts.add(host);
                freeCpu[host] = model.dimensionCount >= CPU ? engine.available(host, CPU) : 0;
                freeRam[host] = model.dimensionCount >= RAM ? engine.available(host, RAM) : 0;
            }
        }
        hosts.sort(Comparator.<Integer>comparingDouble(host -> freeCpu[host])
                .thenComparingDouble(host -> freeRam[host]).reversed().thenComparingInt(Integer::intValue));
        return hosts;
    }

    Map<Integer, List<Request>> victims(Request incoming, Request excluded) {
        final var requiredResources = new HashSet<Integer>();
        final var requiredGroups = new HashSet<Integer>();
        final int members = incoming == null ? 0 : incoming.size();
        for (int member = 0; member < members; member++) {
            final int profile = model.constraints[incoming.resource(member)];
            for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
                final var required = model.rules.linkKind[link] == Relation.COLOCATE_VALUE
                        ? requiredResources : requiredGroups;
                required.add(model.rules.linkTarget[link]);
            }
        }
        final var byHost = new LinkedHashMap<Integer, List<Request>>();
        for (int resource = model.hostCount + 1; resource < model.alloc.length; resource++) {
            final int profile = model.constraints[resource];
            if (model.rules.kind[profile] != Kind.POD_VALUE || model.rules.pinnedHost[profile] != 0
                    || model.rules.linkOffset[profile] != model.rules.linkOffset[profile + 1]
                    || model.rules.diskOffset[profile] != model.rules.diskOffset[profile + 1]
                    || requiredResources.contains(resource) || requiredGroups.contains(model.rules.group[profile])) {
                continue;
            }
            final var current = engine.placement(resource);
            if (current == null) {
                continue;
            }
            final var candidates = byHost.computeIfAbsent(current.host(), ignored -> new ArrayList<>());
            final var request = engine.committedRequest(resource);
            if (candidates.size() < VICTIM_LIMIT && request != excluded && request.size() == 1 && !usesGpu(resource)
                    && engine.canRelocate(request)) {
                candidates.add(request);
            }
        }
        byHost.values().removeIf(List::isEmpty);
        return byHost;
    }

    private boolean usesGpu(int resource) {
        final int allocation = model.alloc[resource];
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            final int type = model.allocations.type[index];
            if ((type == ResourceType.NVIDIA_GPUS_VALUE || type == ResourceType.INTEL_GPUS_VALUE)
                    && model.allocations.amount[index] > 0) {
                return true;
            }
        }
        return false;
    }

    boolean eligible(Request request, int host) {
        for (int member = 0; member < request.size(); member++) {
            final int profile = model.constraints[request.resource(member)];
            final int pinned = model.rules.pinnedHost[profile];
            if (pinned != 0 && pinned != host || !model.candidates.allows(model.rules.candidates[profile], host)) {
                return false;
            }
        }
        return true;
    }

    List<Assignment[]> destinations(Request request, int source, List<Integer> hosts, GreedyPlanner planner) {
        final var result = new ArrayList<Assignment[]>();
        int destinations = 0;
        for (final int host : hosts) {
            if (host != source && eligible(request, host)) {
                addPlans(result, request, host, planner);
                if (++destinations == DESTINATION_LIMIT) {
                    break;
                }
            }
        }
        return result;
    }

    static List<Assignment[]> plans(Request request, int host, GreedyPlanner planner) {
        final var result = new ArrayList<Assignment[]>();
        addPlans(result, request, host, planner);
        return result;
    }

    private static void addPlans(List<Assignment[]> result, Request request, int host, GreedyPlanner planner) {
        int added = 0;
        for (final int numa : planner.numaNodes(host)) {
            final var plans = planner.plan(request, host, numa);
            if (plans != null) {
                result.add(plans);
                if (++added == NUMA_LIMIT) {
                    break;
                }
            }
        }
    }
}
