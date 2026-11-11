package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.TreeMap;

import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

final class BulkModel {
    static final class Unsupported extends RuntimeException {
        Unsupported(String reason) {
            super(reason, null, false, false);
        }
    }

    static final class Node {
        final int host, size;
        final int[] children;
        int cap;

        Node(int host, int size, int cap, int[] children) {
            this.host = host;
            this.size = size;
            this.cap = cap;
            this.children = children;
        }
    }

    private record DomainSet(int[] hosts, int cap) {
    }

    private final SchedulingContext context;
    private final ClusterSpec model;
    private final Request[] requests;
    private final int[] groupSizes, resources, groups;
    private final boolean[] privateGroups;
    final int hosts;
    Node[] nodes;
    int root, depth;

    BulkModel(SchedulingContext context, Request[] requests, int[] groupSizes, CountDp.Work work) {
        this.context = context;
        model = context.model();
        this.requests = requests;
        this.groupSizes = groupSizes;
        hosts = model.hostCount;
        if (requests.length > 100) {
            throw new Unsupported("replica_limit");
        }
        final int members = requests[0].size();
        work.reserve(144L + 8L * requests.length * members + 5L * members);
        resources = new int[Math.multiplyExact(requests.length, members)];
        groups = new int[resources.length];
        privateGroups = new boolean[members];
        int index = 0;
        int service = 0;
        for (final var request : requests) {
            if (request.size() != members) {
                throw new Unsupported("member_stencil");
            }
            int pods = 0;
            int group = 0;
            for (int member = 0; member < members; member++) {
                final int resource = request.resource(member);
                final int profile = model.constraints[resource];
                final int kind = model.rules.kind[profile];
                if (kind == 4) {
                    pods++;
                    group = model.rules.group[profile];
                } else if (kind != 5) {
                    throw new Unsupported("member_kind");
                }
                resources[index] = resource;
                groups[index++] = model.rules.group[profile];
            }
            if (pods != 1 || group == 0 || service != 0 && service != group) {
                throw new Unsupported("service_identity");
            }
            service = group;
        }
        Arrays.sort(resources);
        Arrays.sort(groups);
        final var template = requests[0];
        for (int member = 0; member < members; member++) {
            final int group = group(template.resource(member));
            boolean shared = true;
            for (final var request : requests) {
                shared &= group(request.resource(member)) == group;
                for (int other = 0; other < members; other++) {
                    if ((group == group(template.resource(other)))
                            != (group(request.resource(member)) == group(request.resource(other)))) {
                        throw new Unsupported("group_stencil");
                    }
                }
            }
            if (!shared) {
                privateGroups[member] = true;
                for (final var request : requests) {
                    final int actual = group(request.resource(member));
                    if (actual == 0 || group == 0 || groupSizes[actual] != multiplicity(request, actual)
                            || context.groupCount(actual) != 0) {
                        throw new Unsupported("identity_dependent_group");
                    }
                }
            }
        }
        for (final var request : requests) {
            for (int member = 0; member < members; member++) {
                if (!same(template.resource(member), request.resource(member))) {
                    throw new Unsupported("replica_shape");
                }
                final int a = model.constraints[template.resource(member)];
                final int b = model.constraints[request.resource(member)];
                final int count = model.rules.linkOffset[a + 1] - model.rules.linkOffset[a];
                if (count != model.rules.linkOffset[b + 1] - model.rules.linkOffset[b]) {
                    throw new Unsupported("relative_links");
                }
                for (int link = 0; link < count; link++) {
                    final int left = model.rules.linkOffset[a] + link;
                    final int right = model.rules.linkOffset[b] + link;
                    if (model.rules.linkKind[left] != model.rules.linkKind[right]
                            || model.rules.linkDomain[left] != model.rules.linkDomain[right]
                            || target(template, left) != target(request, right)) {
                        throw new Unsupported("relative_links");
                    }
                }
            }
        }
        final int[] components = new int[members];
        for (int member = 0; member < members; member++) {
            components[member] = member;
        }
        for (int member = 0; member < members; member++) {
            final int profile = model.constraints[template.resource(member)];
            for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
                final int other = role(template, model.rules.linkTarget[link]);
                if (model.rules.linkKind[link] == 0 && model.rules.linkDomain[link] <= 2 && other >= 0) {
                    components[find(components, other)] = find(components, member);
                }
            }
        }
        for (int member = 1; member < members; member++) {
            if (find(components, member) != find(components, 0)) {
                throw new Unsupported("multi_host_unit");
            }
        }
    }

    void tree(CountDp.Work work) {
        final int n = requests.length;
        final var limits = new TreeMap<Long, Integer>();
        for (int member = 0; member < requests[0].size(); member++) {
            if (privateGroups[member]) {
                continue;
            }
            final int profile = model.constraints[requests[0].resource(member)];
            final int group = model.rules.group[profile];
            if (group == 0) {
                continue;
            }
            for (int limit = model.rules.limitOffset[profile]; limit < model.rules.limitOffset[profile + 1]; limit++) {
                final int level = model.rules.limitDomain[limit];
                if (model.rules.limitHard[limit] && level > 2) {
                    final long key = (long) group << 32 | Integer.toUnsignedLong(level);
                    final var previous = limits.get(key);
                    if (previous == null) {
                        work.reserve(96);
                        limits.put(key, model.rules.limitMax[limit]);
                    } else if (model.rules.limitMax[limit] < previous) {
                        limits.put(key, model.rules.limitMax[limit]);
                    }
                }
            }
        }
        final var sets = new ArrayList<DomainSet>();
        for (final var limit : limits.entrySet()) {
            final int group = (int) (limit.getKey() >>> 32);
            final int level = limit.getKey().intValue();
            final int m = multiplicity(requests[0], group);
            work.reserve(16L + 8L * hosts);
            work.charge(hosts);
            final long[] byDomain = new long[hosts];
            int count = 0;
            for (int host = 1; host <= hosts; host++) {
                final int value = domain(host, level);
                if (value != 0) {
                    byDomain[count++] = (long) value << 32 | Integer.toUnsignedLong(host);
                }
            }
            Arrays.sort(byDomain, 0, count);
            for (int start = 0; start < count;) {
                int end = start + 1;
                final int value = (int) (byDomain[start] >>> 32);
                while (end < count && (int) (byDomain[end] >>> 32) == value) {
                    end++;
                }
                final int cap = Math.max(0, Math.min(n, (limit.getValue() - context.domainCount(group, level, value)) / m));
                if (cap < n) {
                    work.reserve(64L + 4L * (end - start));
                    work.charge(end - start);
                    final int[] members = new int[end - start];
                    for (int index = start; index < end; index++) {
                        members[index - start] = (int) byDomain[index];
                    }
                    sets.add(new DomainSet(members, cap));
                }
                start = end;
            }
        }
        sets.sort(Comparator.comparingInt(set -> set.hosts.length));
        work.reserve(128L + 256L * (hosts + 1));
        work.charge(8L * (hosts + 1));
        nodes = new Node[Math.addExact(Math.multiplyExact(hosts, 2), 1)];
        final int[] top = new int[hosts + 1], seen = new int[nodes.length], counts = new int[nodes.length];
        final int[] children = new int[hosts], heights = new int[nodes.length];
        int size = hosts;
        for (int host = 1; host <= hosts; host++) {
            nodes[host] = new Node(host, 1, n, new int[0]);
            top[host] = host;
        }
        int stamp = 0;
        for (final var set : sets) {
            int used = 0;
            stamp++;
            for (final int host : set.hosts) {
                final int id = top[host];
                if (seen[id] != stamp) {
                    seen[id] = stamp;
                    counts[id] = 0;
                    children[used++] = id;
                }
                counts[id]++;
            }
            for (int index = 0; index < used; index++) {
                final int id = children[index];
                if (counts[id] != nodes[id].size) {
                    throw new Unsupported("crossing_domains");
                }
            }
            final int id;
            if (used == 1) {
                id = children[0];
                nodes[id].cap = Math.min(nodes[id].cap, set.cap);
            } else {
                id = ++size;
                work.reserve(48L + 4L * used);
                nodes[id] = new Node(0, set.hosts.length, set.cap, Arrays.copyOf(children, used));
                for (int child = 0; child < used; child++) {
                    heights[id] = Math.max(heights[id], heights[children[child]] + 1);
                }
                if (heights[id] > 5) {
                    throw new Unsupported("tree_depth");
                }
            }
            for (final int host : set.hosts) {
                top[host] = id;
            }
        }
        int used = 0;
        stamp++;
        for (int host = 1; host <= hosts; host++) {
            if (seen[top[host]] != stamp) {
                seen[top[host]] = stamp;
                children[used++] = top[host];
            }
        }
        if (used == 1) {
            root = children[0];
        } else {
            root = ++size;
            work.reserve(48L + 4L * used);
            nodes[root] = new Node(0, hosts, n, Arrays.copyOf(children, used));
            for (int child = 0; child < used; child++) {
                heights[root] = Math.max(heights[root], heights[children[child]] + 1);
            }
        }
        nodes = Arrays.copyOf(nodes, size + 1);
        depth = heights[root];
        if (depth > 5) {
            throw new Unsupported("tree_depth");
        }
    }

    private boolean same(int a, int b) {
        final var r = model.rules;
        final int x = model.constraints[a], y = model.constraints[b];
        if (r.kind[x] != r.kind[y] || r.parent[x] != r.parent[y] || r.ordinal[x] != r.ordinal[y]
                || r.media[x] != r.media[y] || r.alternativeMedia[x] != r.alternativeMedia[y]
                || r.alternativeSpindles[x] != r.alternativeSpindles[y] || r.pinnedHost[x] != r.pinnedHost[y]
                || r.flags[x] != r.flags[y] || r.cpuPower[x] != r.cpuPower[y]) {
            return false;
        }
        if (r.candidates[x] != r.candidates[y]) {
            for (int host = 1; host <= hosts; host++) {
                if (model.candidates.allows(r.candidates[x], host) != model.candidates.allows(r.candidates[y], host)) {
                    return false;
                }
            }
        }
        final int p = model.alloc[a], q = model.alloc[b];
        if (model.allocations.offset[p + 1] - model.allocations.offset[p]
                != model.allocations.offset[q + 1] - model.allocations.offset[q]) {
            return false;
        }
        for (int offset = 0; offset < model.allocations.offset[p + 1] - model.allocations.offset[p]; offset++) {
            final int i = model.allocations.offset[p] + offset, j = model.allocations.offset[q] + offset;
            if (model.allocations.type[i] != model.allocations.type[j]
                    || model.allocations.amount[i] != model.allocations.amount[j]) {
                return false;
            }
        }
        if (r.limitOffset[x + 1] - r.limitOffset[x] != r.limitOffset[y + 1] - r.limitOffset[y]
                || r.diskOffset[x + 1] - r.diskOffset[x] != r.diskOffset[y + 1] - r.diskOffset[y]) {
            return false;
        }
        for (int offset = 0; offset < r.limitOffset[x + 1] - r.limitOffset[x]; offset++) {
            final int i = r.limitOffset[x] + offset, j = r.limitOffset[y] + offset;
            if (r.limitDomain[i] != r.limitDomain[j] || r.limitMax[i] != r.limitMax[j] || r.limitHard[i] != r.limitHard[j]) {
                return false;
            }
        }
        for (int offset = 0; offset < r.diskOffset[x + 1] - r.diskOffset[x]; offset++) {
            final int i = r.diskOffset[x] + offset, j = r.diskOffset[y] + offset;
            if (r.diskMedia[i] != r.diskMedia[j] || r.diskLayout[i] != r.diskLayout[j]
                    || r.diskExtents[i] != r.diskExtents[j] || r.diskSpindles[i] != r.diskSpindles[j]
                    || !equal(r.diskIops[i], r.diskIops[j]) || !equal(r.diskIopsCap[i], r.diskIopsCap[j])
                    || r.minDevices[i] != r.minDevices[j] || r.maxDevices[i] != r.maxDevices[j]
                    || r.minStripes[i] != r.minStripes[j] || r.maxStripes[i] != r.maxStripes[j]
                    || r.diskFlags[i] != r.diskFlags[j] || r.metadataExtents[i] != r.metadataExtents[j]) {
                return false;
            }
        }
        return true;
    }

    private long target(Request request, int link) {
        final int target = model.rules.linkTarget[link];
        if (model.rules.linkKind[link] == 0) {
            final int member = role(request, target);
            if (member >= 0) {
                return -member - 1L;
            }
            if (Arrays.binarySearch(resources, target) >= 0) {
                throw new Unsupported("inter_unit_dependency");
            }
            if (context.placement(target) == null) {
                throw new Unsupported("dependencies_not_ready");
            }
        } else {
            for (int member = 0; member < request.size(); member++) {
                if (group(request.resource(member)) == target) {
                    if (!privateGroups[member]) {
                        throw new Unsupported("inter_unit_dependency");
                    }
                    return -member - 1L;
                }
            }
            if (Arrays.binarySearch(groups, target) >= 0) {
                throw new Unsupported("inter_unit_dependency");
            }
            if (context.groupCount(target) == 0 || model.rules.linkKind[link] == 1
                    && context.groupCount(target) != groupSizes[target]) {
                throw new Unsupported("dependencies_not_ready");
            }
        }
        return target;
    }

    private int group(int resource) {
        return model.rules.group[model.constraints[resource]];
    }

    private int multiplicity(Request request, int group) {
        int count = 0;
        for (int member = 0; member < request.size(); member++) {
            count += group(request.resource(member)) == group ? 1 : 0;
        }
        return count;
    }

    private int domain(int host, int level) {
        final int profile = model.constraints[host];
        for (int index = model.rules.domainOffset[profile]; index < model.rules.domainOffset[profile + 1]; index++) {
            if (model.rules.domainLevel[index] == level) {
                return model.rules.domainValue[index];
            }
        }
        return 0;
    }

    private static int role(Request request, int resource) {
        for (int member = 0; member < request.size(); member++) {
            if (request.resource(member) == resource) {
                return member;
            }
        }
        return -1;
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static boolean equal(double a, double b) {
        return a == b || Double.isNaN(a) && Double.isNaN(b);
    }
}
