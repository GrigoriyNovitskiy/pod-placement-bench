package one.cloud.benchmark.engine;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.TreeSet;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.model.Placement;
import one.cloud.benchmark.model.PlacementModel;
import one.cloud.benchmark.model.PlacementRequest;

public final class PlacementState implements PlacementContext {
    private static final long[] NO_LINKS = new long[0];
    private final PlacementModel model;
    private final PlacementCapacity capacity;
    private final Thread owner = Thread.currentThread();
    private final Placement[] placements;
    private final Unit[] units;
    private final Placement[] tentative;
    private final int[] windowOwner, seen, groupSizes, groupCounts, tentativeGroups;
    private final long[] hostRevisions, domains;
    private final Counts counts = new Counts(), tentativeCounts = new Counts(), incoming = new Counts();
    private final PlacementCapacity.Debit trial = new PlacementCapacity.Debit();
    private PlacementAlgorithm algorithm;
    private IdentityHashMap<PlacementRequest, Integer> pending;
    private long revision;
    private int nextRequest, sequence, tentativeSize;
    private boolean notifying;
    private Throwable failure;

    public PlacementState(PlacementModel model) {
        this.model = Objects.requireNonNull(model);
        capacity = new PlacementCapacity(model);
        placements = new Placement[model.alloc.length];
        units = new Unit[placements.length];
        tentative = new Placement[placements.length];
        windowOwner = new int[placements.length];
        seen = new int[placements.length];
        hostRevisions = new long[model.hostCount + 1];
        int maxGroup = 0;
        for (final int group : model.rules.group) {
            maxGroup = Math.max(maxGroup, group);
        }
        for (int index = 0; index < model.rules.linkTarget.length; index++) {
            if (model.rules.linkKind[index] != Relation.COLOCATE_VALUE) {
                maxGroup = Math.max(maxGroup, model.rules.linkTarget[index]);
            }
        }
        groupSizes = new int[maxGroup + 1];
        groupCounts = new int[groupSizes.length];
        tentativeGroups = new int[groupSizes.length];
        final var keys = new TreeSet<Long>();
        for (int resource = 1; resource < placements.length; resource++) {
            final int profile = model.constraints[resource];
            final int kind = model.rules.kind[profile];
            if (kind == Kind.HOST_VALUE) {
                keys.add(domainKey(1, resource));
                keys.add(domainKey(2, resource));
            } else if (kind == Kind.NUMA_VALUE) {
                keys.add(domainKey(2, resource));
            } else if (kind != Kind.DISK_VALUE && model.rules.group[profile] != 0) {
                groupSizes[model.rules.group[profile]]++;
            }
            for (int index = model.rules.domainOffset[profile]; index < model.rules.domainOffset[profile + 1]; index++) {
                keys.add(domainKey(model.rules.domainLevel[index], model.rules.domainValue[index]));
            }
        }
        domains = keys.stream().mapToLong(Long::longValue).toArray();
    }

    public void schedule(PlacementAlgorithm algorithm, PlacementRequest... requests) throws Exception {
        checkMutable();
        Objects.requireNonNull(algorithm);
        if (pending != null || algorithm.context != this || this.algorithm != null && this.algorithm != algorithm) {
            throw new IllegalStateException("A state belongs to one algorithm and one scheduling window");
        }
        final var announced = requests.clone();
        final var window = new IdentityHashMap<PlacementRequest, Integer>();
        try {
            for (int index = 0; index < announced.length; index++) {
                final var request = Objects.requireNonNull(announced[index]);
                if (window.put(request, index) != null) {
                    throw new IllegalArgumentException("Repeated request in scheduling window");
                }
                for (int member = 0; member < request.size(); member++) {
                    final int resource = request.resource(member);
                    checkResource(resource);
                    if (windowOwner[resource] != 0 || placements[resource] != null) {
                        throw new IllegalArgumentException("Scheduling units must be disjoint and unplaced");
                    }
                    windowOwner[resource] = index + 1;
                }
            }
        } catch (RuntimeException | Error failure) {
            Arrays.fill(windowOwner, 0);
            throw failure;
        }
        this.algorithm = algorithm;
        pending = window;
        nextRequest = 0;
        try {
            algorithm.schedule(announced);
            if (failure != null) {
                throw new IllegalStateException("Algorithm suppressed a notification failure", failure);
            }
        } catch (Exception | Error failure) {
            if (this.failure == null) {
                this.failure = failure;
            }
            throw failure;
        } finally {
            for (final var request : window.keySet()) {
                for (int member = 0; member < request.size(); member++) {
                    windowOwner[request.resource(member)] = 0;
                }
            }
            pending = null;
        }
    }

    public void remove(PlacementRequest request) {
        checkMutable();
        if (pending != null) {
            throw new IllegalStateException("Removal is outside the scheduling window");
        }
        final int resource = request.resource(0);
        checkResource(resource);
        final var unit = units[resource];
        if (unit == null || unit.request != request) {
            throw new IllegalArgumentException("The exact committed unit must be removed");
        }
        for (final var plan : unit.plans) {
            update(plan, 1, tentativeGroups, tentativeCounts);
        }
        try {
            for (final var plan : unit.plans) {
                if (!removable(unit, plan)) {
                    throw new IllegalStateException("A live placement depends on this unit");
                }
            }
        } finally {
            for (final var plan : unit.plans) {
                update(plan, -1, tentativeGroups, tentativeCounts);
            }
        }
        capacity.release(unit.debit);
        for (final long link : unit.outgoing) {
            incoming.add(link, -1);
        }
        for (final var plan : unit.plans) {
            placements[plan.resource()] = null;
            units[plan.resource()] = null;
            update(plan, -1, groupCounts, counts);
        }
        changed(unit);
        notify(unit.plans, false);
    }

    @Override
    public PlacementModel model() {
        checkThread();
        return model;
    }

    @Override
    public long revision() {
        checkThread();
        return revision;
    }

    @Override
    public long capacityRevision(int host) {
        checkThread();
        if (host <= 0 || host > model.hostCount) {
            throw new IllegalArgumentException("Invalid host");
        }
        return hostRevisions[host];
    }

    @Override
    public double available(int resource, int dimension) {
        checkThread();
        return capacity.available(resource, dimension);
    }

    @Override
    public double available(int resource, int dimension, int media) {
        checkThread();
        return capacity.available(resource, dimension, media);
    }

    @Override
    public Placement placement(int resource) {
        checkThread();
        checkResource(resource);
        return placements[resource];
    }

    @Override
    public int groupCount(int group) {
        checkThread();
        checkGroup(group);
        return groupCounts[group];
    }

    @Override
    public int domainCount(int group, int level, int value) {
        checkThread();
        checkGroup(group);
        return count(counts, group, level, value);
    }

    @Override
    public boolean canPlace(PlacementRequest request, Placement... plans) {
        checkThread();
        if (failure != null) {
            throw new IllegalStateException("Failed placement state", failure);
        }
        try {
            return prepare(request, plans, trial) != null;
        } finally {
            clearTentative(plans);
        }
    }

    @Override
    public void place(PlacementRequest request, Placement... plans) {
        checkMutable();
        final Unit unit;
        try {
            final var debit = prepare(request, plans, new PlacementCapacity.Debit());
            if (debit == null) {
                throw new IllegalArgumentException("Infeasible placement");
            }
            final var copies = new Placement[request.size()];
            for (int member = 0; member < copies.length; member++) {
                copies[member] = tentative[request.resource(member)].copy();
            }
            unit = new Unit(request, copies, debit, capacity.changedHosts(debit), outgoing(copies));
        } finally {
            clearTentative(plans);
        }
        capacity.allocate(unit.debit);
        for (final long link : unit.outgoing) {
            incoming.add(link, 1);
        }
        for (final var plan : unit.plans) {
            placements[plan.resource()] = plan;
            units[plan.resource()] = unit;
            update(plan, 1, groupCounts, counts);
        }
        nextRequest = pending.get(request) + 1;
        changed(unit);
        notify(unit.plans, true);
    }

    private PlacementCapacity.Debit prepare(PlacementRequest request, Placement[] plans, PlacementCapacity.Debit trial) {
        final var index = pending == null ? null : pending.get(request);
        if (index == null || index < nextRequest || plans.length != request.size()) {
            throw new IllegalArgumentException("Request is not pending or has incomplete assignments");
        }
        final int mark = mark();
        for (final var plan : plans) {
            Objects.requireNonNull(plan);
            checkResource(plan.resource());
            if (windowOwner[plan.resource()] != index + 1 || seen[plan.resource()] == mark
                    || placements[plan.resource()] != null || plan.host() <= 0 || plan.host() > model.hostCount) {
                throw new IllegalArgumentException("Invalid, repeated or unrelated assignment");
            }
            seen[plan.resource()] = mark;
            tentative[plan.resource()] = plan;
            tentativeSize++;
            update(plan, 1, tentativeGroups, tentativeCounts);
        }
        final var debit = capacity.prepare(plans, trial);
        for (final var plan : plans) {
            final int profile = model.constraints[plan.resource()];
            final int hostProfile = model.constraints[plan.host()];
            if ((model.rules.flags[hostProfile] & 1) != 0
                    || !model.candidates.allows(model.rules.candidates[profile], plan.host())
                    || model.rules.pinnedHost[profile] != 0 && model.rules.pinnedHost[profile] != plan.host()
                    || !linksFit(plan)) {
                return null;
            }
            for (int limit = model.rules.limitOffset[profile]; limit < model.rules.limitOffset[profile + 1]; limit++) {
                if (model.rules.limitHard[limit]) {
                    final int level = model.rules.limitDomain[limit];
                    final int value = domain(plan, level);
                    if (value == 0 || count(counts, group(plan), level, value)
                            + count(tentativeCounts, group(plan), level, value) > model.rules.limitMax[limit]) {
                        return null;
                    }
                }
            }
        }
        return debit;
    }

    private boolean linksFit(Placement plan) {
        final int profile = model.constraints[plan.resource()];
        for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
            final int target = model.rules.linkTarget[link];
            final int kind = model.rules.linkKind[link];
            final int level = model.rules.linkDomain[link];
            if (kind == Relation.COLOCATE_VALUE) {
                final var other = tentative[target] != null ? tentative[target] : placements[target];
                if (other == null || domain(plan, level) == 0 || domain(plan, level) != domain(other, level)) {
                    return false;
                }
            } else if (kind == Relation.READY_GROUP_VALUE) {
                final int count = groupCounts[target] + tentativeGroups[target];
                if (groupSizes[target] == 0 || count != groupSizes[target]) {
                    return false;
                }
            } else {
                final int value = domain(plan, level);
                final int count = count(counts, target, level, value) + count(tentativeCounts, target, level, value);
                if (value == 0 || count == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean removable(Unit unit, Placement plan) {
        if (external(unit, (long) plan.resource() << 32)) {
            return false;
        }
        final int group = group(plan);
        if (group == 0) {
            return true;
        }
        if (external(unit, (long) group << 32 | 1L)
                || !removable(unit, group, 1, plan.host())
                || !removable(unit, group, 2, plan.numa() == 0 ? plan.host() : plan.numa())) {
            return false;
        }
        final int profile = model.constraints[plan.host()];
        for (int index = model.rules.domainOffset[profile]; index < model.rules.domainOffset[profile + 1]; index++) {
            final int level = model.rules.domainLevel[index];
            if (level > 2 && !removable(unit, group, level, model.rules.domainValue[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean removable(Unit unit, int group, int level, int value) {
        final int id = Arrays.binarySearch(domains, domainKey(level, value));
        final long key = (long) group << 32 | id + 1L;
        return id < 0 || counts.get(key) > tentativeCounts.get(key)
                || !external(unit, (long) group << 32 | id + 2L);
    }

    private boolean external(Unit unit, long key) {
        final int count = incoming.get(key);
        if (count == 0) {
            return false;
        }
        final int found = Arrays.binarySearch(unit.outgoing, key);
        if (found < 0) {
            return true;
        }
        int low = 0;
        int high = found;
        while (low < high) {
            final int middle = low + (high - low) / 2;
            if (unit.outgoing[middle] < key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        final int first = low;
        low = found + 1;
        high = unit.outgoing.length;
        while (low < high) {
            final int middle = low + (high - low) / 2;
            if (unit.outgoing[middle] <= key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return count > low - first;
    }

    private long[] outgoing(Placement[] plans) {
        int size = 0;
        for (final var plan : plans) {
            final int profile = model.constraints[plan.resource()];
            size = Math.addExact(size, model.rules.linkOffset[profile + 1] - model.rules.linkOffset[profile]);
        }
        if (size == 0) {
            return NO_LINKS;
        }
        final long[] keys = new long[size];
        int next = 0;
        for (final var plan : plans) {
            final int profile = model.constraints[plan.resource()];
            for (int link = model.rules.linkOffset[profile]; link < model.rules.linkOffset[profile + 1]; link++) {
                final int target = model.rules.linkTarget[link];
                int type = model.rules.linkKind[link];
                if (type == Relation.SHARED_GROUP_VALUE) {
                    final int level = model.rules.linkDomain[link];
                    type = Arrays.binarySearch(domains, domainKey(level, domain(plan, level))) + 2;
                }
                keys[next++] = (long) target << 32 | Integer.toUnsignedLong(type);
            }
        }
        Arrays.sort(keys);
        return keys;
    }

    private void clearTentative(Placement[] plans) {
        for (int index = 0; index < tentativeSize; index++) {
            final var plan = plans[index];
            tentative[plan.resource()] = null;
            update(plan, -1, tentativeGroups, tentativeCounts);
        }
        tentativeSize = 0;
    }

    private void update(Placement plan, int delta, int[] groups, Counts counters) {
        final int group = group(plan);
        if (group == 0) {
            return;
        }
        groups[group] += delta;
        add(counters, group, 1, plan.host(), delta);
        add(counters, group, 2, plan.numa() == 0 ? plan.host() : plan.numa(), delta);
        final int profile = model.constraints[plan.host()];
        for (int index = model.rules.domainOffset[profile]; index < model.rules.domainOffset[profile + 1]; index++) {
            final int level = model.rules.domainLevel[index];
            if (level > 2) {
                add(counters, group, level, model.rules.domainValue[index], delta);
            }
        }
    }

    private void add(Counts counters, int group, int level, int value, int delta) {
        final int id = Arrays.binarySearch(domains, domainKey(level, value));
        if (id >= 0) {
            counters.add((long) group << 32 | id + 1L, delta);
        }
    }

    private int count(Counts counters, int group, int level, int value) {
        if (group == 0 || value == 0) {
            return 0;
        }
        final int id = Arrays.binarySearch(domains, domainKey(level, value));
        return id < 0 ? 0 : counters.get((long) group << 32 | id + 1L);
    }

    private int domain(Placement plan, int level) {
        if (level == 1) {
            return plan.host();
        }
        if (level == 2) {
            return plan.numa() == 0 ? plan.host() : plan.numa();
        }
        final int profile = model.constraints[plan.host()];
        for (int index = model.rules.domainOffset[profile]; index < model.rules.domainOffset[profile + 1]; index++) {
            if (model.rules.domainLevel[index] == level) {
                return model.rules.domainValue[index];
            }
        }
        return 0;
    }

    private int group(Placement plan) {
        return model.rules.group[model.constraints[plan.resource()]];
    }

    private void changed(Unit unit) {
        revision++;
        for (final int host : unit.hosts) {
            hostRevisions[host]++;
        }
    }

    private void notify(Placement[] plans, boolean placed) {
        notifying = true;
        try {
            for (final var plan : plans) {
                if (placed) {
                    algorithm.placed(plan);
                } else {
                    algorithm.removed(plan);
                }
            }
        } catch (RuntimeException | Error failure) {
            this.failure = failure;
            throw failure;
        } finally {
            notifying = false;
        }
    }

    private int mark() {
        if (sequence == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            sequence = 0;
        }
        return ++sequence;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Placement state is confined to its owner thread");
        }
    }

    private void checkMutable() {
        checkThread();
        if (failure != null || notifying) {
            throw new IllegalStateException("Failed state or reentrant state change", failure);
        }
    }

    private void checkResource(int resource) {
        if (resource <= 0 || resource >= placements.length
                || model.rules.kind[model.constraints[resource]] >= Kind.HOST_VALUE
                && model.rules.kind[model.constraints[resource]] <= Kind.DISK_VALUE) {
            throw new IllegalArgumentException("Invalid placement resource");
        }
    }

    private void checkGroup(int group) {
        if (group < 0 || group >= groupCounts.length) {
            throw new IllegalArgumentException("Invalid group");
        }
    }

    private static long domainKey(int level, int value) {
        return (long) level << 32 | Integer.toUnsignedLong(value);
    }

    private record Unit(PlacementRequest request, Placement[] plans, PlacementCapacity.Debit debit, int[] hosts, long[] outgoing) {
    }

    private static final class Counts {
        private long[] keys = new long[16];
        private int[] values = new int[16];
        private int size;

        private int get(long key) {
            final int index = slot(key);
            return keys[index] == 0 ? 0 : values[index];
        }

        private void add(long key, int delta) {
            int index = slot(key);
            if (keys[index] == 0 && size * 2 >= keys.length) {
                final var previousKeys = keys;
                final var previousValues = values;
                keys = new long[keys.length * 2];
                values = new int[keys.length];
                size = 0;
                for (int previous = 0; previous < previousKeys.length; previous++) {
                    if (previousKeys[previous] != 0) {
                        add(previousKeys[previous], previousValues[previous]);
                    }
                }
                index = slot(key);
            }
            final int value = values[index] + delta;
            if (value < 0) {
                throw new IllegalStateException("Negative placement count");
            }
            if (keys[index] == 0) {
                keys[index] = key;
                size++;
            }
            values[index] = value;
            if (value == 0) {
                keys[index] = 0;
                size--;
                int next = index + 1 & keys.length - 1;
                while (keys[next] != 0) {
                    final long moved = keys[next];
                    final int count = values[next];
                    keys[next] = 0;
                    values[next] = 0;
                    size--;
                    add(moved, count);
                    next = next + 1 & keys.length - 1;
                }
            }
        }

        private int slot(long key) {
            long hash = key;
            hash = (hash ^ hash >>> 33) * 0xff51afd7ed558ccdL;
            hash = (hash ^ hash >>> 33) * 0xc4ceb9fe1a85ec53L;
            int index = (int) (hash ^ hash >>> 33) & keys.length - 1;
            while (keys[index] != 0 && keys[index] != key) {
                index = index + 1 & keys.length - 1;
            }
            return index;
        }

    }
}
