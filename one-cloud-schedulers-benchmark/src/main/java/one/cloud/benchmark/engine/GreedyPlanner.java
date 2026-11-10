package one.cloud.benchmark.engine;

import java.util.Arrays;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

public final class GreedyPlanner {
    private static final Assignment[] EMPTY = new Assignment[0];
    private static final int[] NO_GPUS = new int[0];
    private final SchedulingContext context;
    private final ClusterSpec model;
    private final CapacityLedger capacity;
    private final int[][] inventory, nodes, devices;
    private final double[] demand;
    private final CapacityLedger.Debit tentativeDebit = new CapacityLedger.Debit();
    private Member[] members = new Member[0];
    private int[] component = new int[0], candidates = new int[0], chosen = new int[0];
    private Assignment[] local = EMPTY;
    private int memberCount, anchor, host, candidateCount;
    private boolean ambiguous;
    private long planCalls;

    public GreedyPlanner(SchedulingContext context) {
        this.context = context;
        model = context.model();
        capacity = new CapacityLedger(model, false);
        demand = new double[model.dimensionCount + 1];
        inventory = new int[model.hostCount + 1][];
        nodes = new int[inventory.length][];
        devices = new int[inventory.length][];
        final int[] counts = new int[inventory.length];
        final int[] numaCounts = new int[inventory.length];
        final int[] deviceCounts = new int[inventory.length];
        for (int resource = 1; resource < model.alloc.length; resource++) {
            final int profile = model.constraints[resource];
            final int kind = model.rules.kind[profile];
            if (kind >= 1 && kind <= 3) {
                final int owner = kind == 1 ? resource : model.rules.parent[profile];
                counts[owner]++;
                numaCounts[owner] += kind == 2 ? 1 : 0;
                deviceCounts[owner] += kind == 3 ? 1 : 0;
            }
        }
        for (int owner = 1; owner < inventory.length; owner++) {
            inventory[owner] = new int[counts[owner]];
            nodes[owner] = new int[numaCounts[owner] + 1];
            devices[owner] = new int[deviceCounts[owner]];
        }
        Arrays.fill(counts, 0);
        Arrays.fill(numaCounts, 1);
        Arrays.fill(deviceCounts, 0);
        for (int resource = 1; resource < model.alloc.length; resource++) {
            final int profile = model.constraints[resource];
            final int kind = model.rules.kind[profile];
            if (kind >= 1 && kind <= 3) {
                final int owner = kind == 1 ? resource : model.rules.parent[profile];
                inventory[owner][counts[owner]++] = resource;
                if (kind == 2) {
                    nodes[owner][numaCounts[owner]++] = resource;
                } else if (kind == 3) {
                    devices[owner][deviceCounts[owner]++] = resource;
                }
            }
        }
    }

    public int[] numaNodes(int host) {
        checkHost(host);
        return nodes[host];
    }

    /** Scores complete, same-host plans returned by this planner; use only on its owner thread. */
    public ToDoubleFunction<Assignment[]> normalizedResidualL2Scorer() {
        return capacity.normalizedResidualL2Scorer(context, inventory);
    }

    public long planCalls() {
        return planCalls;
    }

    public Assignment[] plan(Request request, int host, int numa) {
        planCalls++;
        checkHost(host);
        if (numa < 0 || numa != 0 && (numa >= model.constraints.length
                || model.rules.kind[model.constraints[numa]] != 2 || model.rules.parent[model.constraints[numa]] != host)) {
            throw new IllegalArgumentException("NUMA does not belong to host");
        }
        for (int index = 0; index < request.size(); index++) {
            final int profile = model.constraints[request.resource(index)];
            if (!model.candidates.allows(model.rules.candidates[profile], host)
                    || model.rules.pinnedHost[profile] != 0 && model.rules.pinnedHost[profile] != host) {
                return null;
            }
        }
        configure(request, host);
        capacity.load(context, inventory[host]);
        if (!build(numa)) {
            return null;
        }
        final var plans = new Assignment[memberCount];
        for (int index = 0; index < memberCount; index++) {
            final var member = members[index];
            plans[index] = new Assignment(member.resource, host, member.numa,
                    Arrays.copyOf(member.offsets, member.diskCount + 1), Arrays.copyOf(member.devices, member.deviceCount),
                    member.gpus.length == 0 ? NO_GPUS : member.gpus.clone());
        }
        return context.canPlace(request, plans) ? plans : null;
    }

    public int fitCount(Request request, int host, int cap) {
        return fitCount(request, host, EMPTY, cap);
    }

    public int fitCount(Request request, int host, Assignment[] tentative, int cap) {
        if (cap < 0) {
            throw new IllegalArgumentException("Fit-count cap must be nonnegative");
        }
        configure(request, host);
        capacity.load(context, inventory[host]);
        if (local.length < tentative.length) {
            local = new Assignment[tentative.length];
        }
        int count = 0;
        for (final var plan : tentative) {
            if (plan.host() == host) {
                local[count++] = plan;
            }
        }
        if (count != 0) {
            final var debit = capacity.prepare(local, count, tentativeDebit);
            Arrays.fill(local, 0, count, null);
            if (debit == null) {
                throw new IllegalArgumentException("Tentative placement exceeds physical capacity");
            }
            capacity.allocate(debit);
        }
        if (memberCount == 1 && members[0].diskCount == 0
                && (model.gpus == null || model.gpus.demand(members[0].resource, 11) == 0
                        && model.gpus.demand(members[0].resource, 17) == 0)) {
            ambiguous = false;
            final int fit = scalarCount(members[0].resource, cap);
            if (!ambiguous || fit == 0) {
                return fit;
            }
        }
        int fit = 0;
        while (fit < cap && build(-1)) {
            fit++;
        }
        return fit;
    }

    private void configure(Request request, int host) {
        this.host = host;
        memberCount = request.size();
        if (members.length < memberCount) {
            final int previous = members.length;
            members = Arrays.copyOf(members, memberCount);
            component = Arrays.copyOf(component, memberCount);
            for (int index = previous; index < members.length; index++) {
                members[index] = new Member();
            }
        }
        if (candidates.length < devices[host].length) {
            candidates = new int[devices[host].length];
            chosen = new int[candidates.length];
        }
        for (int index = 0; index < memberCount; index++) {
            final var member = members[index];
            member.resource = request.resource(index);
            final int gpuCount = capacity.gpuCount(member.resource, 11) + capacity.gpuCount(member.resource, 17);
            if (gpuCount != member.gpus.length) {
                member.gpus = model.gpus != null && gpuCount <= model.gpus.end(host, 17) - model.gpus.first(host, 11)
                        ? new int[gpuCount] : NO_GPUS;
            }
            final int profile = model.constraints[member.resource];
            member.diskCount = model.rules.diskOffset[profile + 1] - model.rules.diskOffset[profile];
            if (member.offsets.length < member.diskCount + 1) {
                member.offsets = new int[member.diskCount + 1];
            }
            component[index] = index;
        }
        for (int index = 0; index < memberCount; index++) {
            final int profile = model.constraints[members[index].resource];
            for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
                if (model.rules.linkKind[link] == 0 && model.rules.linkDomain[link] == 2) {
                    for (int target = 0; target < memberCount; target++) {
                        if (members[target].resource == model.rules.linkTarget[link]) {
                            final int first = root(index);
                            final int second = root(target);
                            component[Math.max(first, second)] = Math.min(first, second);
                        }
                    }
                }
            }
        }
        anchor = root(0);
        for (int index = 0; index < memberCount; index++) {
            if (numaAware(members[index].resource)) {
                anchor = root(index);
                break;
            }
        }
    }

    private boolean build(int anchorNuma) {
        for (int index = 0; index < memberCount; index++) {
            members[index].allocated = false;
        }
        for (int group = 0; group < memberCount; group++) {
            if (root(group) != group) {
                continue;
            }
            boolean allocated = false;
            for (final int node : nodes[host]) {
                if (group == anchor && anchorNuma >= 0 && node != anchorNuma) {
                    continue;
                }
                boolean fits = true;
                for (int index = 0; index < memberCount; index++) {
                    if (root(index) == group && !resource(members[index], node)) {
                        fits = false;
                        break;
                    }
                }
                if (fits) {
                    allocated = true;
                    break;
                }
                for (int index = 0; index < memberCount; index++) {
                    if (root(index) == group) {
                        release(members[index]);
                    }
                }
            }
            if (!allocated) {
                for (int index = 0; index < memberCount; index++) {
                    release(members[index]);
                }
                return false;
            }
        }
        return true;
    }

    private boolean resource(Member member, int numa) {
        member.numa = numa;
        member.deviceCount = 0;
        Arrays.fill(member.offsets, 0, member.diskCount + 1, 0);
        if (!capacity.selectGpus(member.resource, host, member.gpus)) {
            return false;
        }
        var debit = capacity.preparePartial(member.resource, host, numa, member.offsets, member.devices, 0, 0,
                member.gpus, member.debit);
        if (debit == null) {
            return false;
        }
        capacity.allocate(debit);
        member.allocated = true;
        final int first = model.rules.diskOffset[model.constraints[member.resource]];
        for (int index = 0; index < member.diskCount; index++) {
            final int disk = first + index;
            eligible(disk, numa, false);
            boolean placed = false;
            for (int count = Math.max(1, model.rules.minDevices[disk]);
                    count <= Math.min(candidateCount, model.rules.maxDevices[disk]); count++) {
                final int stripes = CapacityLedger.stripes(model.rules.diskLayout[disk], count);
                if (stripes >= model.rules.minStripes[disk] && stripes <= model.rules.maxStripes[disk]
                        && choose(disk, count, stripes) && volume(member, index, count)) {
                    placed = true;
                    break;
                }
            }
            if (!placed && model.rules.diskMedia[disk] == 2 && !CapacityLedger.reserved(model.rules.diskLayout[disk])
                    && (model.rules.diskFlags[disk] & 1) == 0) {
                eligible(disk, numa, true);
                for (int next = 0; next < candidateCount; next++) {
                    chosen[0] = candidates[next];
                    if (volume(member, index, 1)) {
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed && (model.rules.diskFlags[disk] & 2) == 0) {
                release(member);
                return false;
            }
        }
        return true;
    }

    private boolean volume(Member member, int disk, int count) {
        final int previous = member.deviceCount;
        if (member.devices.length < previous + count) {
            member.devices = Arrays.copyOf(member.devices, Math.max(previous + count, member.devices.length * 2));
        }
        System.arraycopy(chosen, 0, member.devices, previous, count);
        member.deviceCount += count;
        Arrays.fill(member.offsets, disk + 1, member.diskCount + 1, member.deviceCount);
        capacity.release(member.debit);
        final var next = capacity.preparePartial(member.resource, host, member.numa, member.offsets, member.devices,
                member.deviceCount, disk + 1, member.gpus, member.trial);
        if (next != null) {
            member.trial = member.debit;
            member.debit = next;
            capacity.allocate(member.debit);
            return true;
        }
        member.deviceCount = previous;
        Arrays.fill(member.offsets, disk + 1, member.diskCount + 1, previous);
        capacity.allocate(member.debit);
        return false;
    }

    private void release(Member member) {
        if (member.allocated) {
            capacity.release(member.debit);
            member.allocated = false;
        }
    }

    private void eligible(int disk, int numa, boolean alternative) {
        candidateCount = 0;
        final int media = model.rules.diskMedia[disk];
        for (final int device : devices[host]) {
            final int profile = model.constraints[device];
            if ((model.rules.flags[profile] & 1) != 0
                    || (alternative ? model.rules.media[profile] == media || model.rules.alternativeMedia[profile] != media
                            : model.rules.media[profile] != media)
                    || capacity.available(device, 18) <= 0
                    || (model.rules.diskFlags[disk] & 1) != 0 && !capacity.wholeAvailable(device)
                    || media == 4 && model.rules.ordinal[profile] != 0
                            && (numa == 0 || model.rules.ordinal[model.constraints[numa]] != model.rules.ordinal[profile])) {
                continue;
            }
            int position = candidateCount;
            while (position > 0 && capacity.available(candidates[position - 1], 18) > capacity.available(device, 18)) {
                candidates[position] = candidates[position - 1];
                position--;
            }
            candidates[position] = device;
            candidateCount++;
        }
    }

    private boolean choose(int disk, int count, int stripes) {
        final long extents = Math.addExact(model.rules.diskExtents[disk] / stripes
                + (model.rules.diskExtents[disk] % stripes == 0 ? 0 : 1), model.rules.metadataExtents[disk]);
        final double spindles = Math.ceil(model.rules.diskSpindles[disk] / stripes);
        final double iops = Double.isNaN(model.rules.diskIops[disk]) ? 0
                : Math.min(model.rules.diskIops[disk] / stripes, Double.isNaN(model.rules.diskIopsCap[disk])
                        ? Double.POSITIVE_INFINITY : model.rules.diskIopsCap[disk]);
        int size = 0;
        for (int index = 0; index < candidateCount; index++) {
            final int device = candidates[index];
            if (capacity.available(device, 18) >= extents
                    && capacity.available(device, 19, model.rules.diskMedia[disk]) >= spindles
                    && capacity.available(device, 20) >= iops) {
                chosen[size++] = device;
                if (size == count) {
                    return true;
                }
            }
        }
        return false;
    }

    private int scalarCount(int resource, int cap) {
        if ((model.rules.flags[model.constraints[host]] & 1) != 0) {
            return 0;
        }
        Arrays.fill(demand, 0);
        final int allocation = model.alloc[resource];
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            demand[model.allocations.type[index]] = model.allocations.amount[index];
        }
        if (demand.length > 15 && demand[15] > 0) {
            demand[1] = demand[15] * model.rules.cpuPower[model.constraints[host]];
        }
        int global = cap;
        boolean numa = false;
        for (int dimension = 1; dimension < demand.length; dimension++) {
            if (demand[dimension] > 0) {
                global = Math.min(global, divide(capacity.available(host, dimension), demand[dimension], cap));
                numa |= CapacityLedger.numaAware(dimension);
            }
        }
        if (!numa || nodes[host].length == 1) {
            return global;
        }
        int total = 0;
        for (int index = 1; index < nodes[host].length && total < global; index++) {
            int fit = cap;
            for (int dimension = 1; dimension < demand.length; dimension++) {
                if (demand[dimension] > 0 && CapacityLedger.numaAware(dimension)) {
                    fit = Math.min(fit, divide(capacity.available(nodes[host][index], dimension), demand[dimension], cap));
                }
            }
            total += Math.min(fit, global - total);
        }
        return total;
    }

    private int divide(double capacity, double demand, int cap) {
        final double fit = Math.floor((capacity + Math.ulp(capacity) * 4) / demand);
        final int bounded = fit >= cap ? cap : Math.max(0, (int) fit);
        if (bounded > 0 && demand != Math.rint(demand)) {
            final double error = (bounded + 1.0) * Math.ulp(Math.max(capacity, demand * bounded)) * 4;
            ambiguous |= Math.abs(capacity - demand * bounded) <= error;
        }
        return bounded;
    }

    private boolean numaAware(int resource) {
        final int allocation = model.alloc[resource];
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            if (model.allocations.amount[index] > 0 && CapacityLedger.numaAware(model.allocations.type[index])) {
                return true;
            }
        }
        final int profile = model.constraints[resource];
        for (int disk = model.rules.diskOffset[profile]; disk < model.rules.diskOffset[profile + 1]; disk++) {
            if (model.rules.diskMedia[disk] == 4) {
                return true;
            }
        }
        return false;
    }

    private int root(int index) {
        while (component[index] != index) {
            index = component[index];
        }
        return index;
    }

    private void checkHost(int host) {
        if (host <= 0 || host >= inventory.length) {
            throw new IllegalArgumentException("Invalid host");
        }
    }

    private static final class Member {
        private int resource, numa, diskCount, deviceCount;
        private int[] offsets = new int[1], devices = new int[0];
        private int[] gpus = NO_GPUS;
        private boolean allocated;
        private CapacityLedger.Debit debit = new CapacityLedger.Debit(), trial = new CapacityLedger.Debit();
    }
}
