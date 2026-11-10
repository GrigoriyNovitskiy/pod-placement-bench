package one.cloud.schedulers;

import java.util.Arrays;

import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.AUTO_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.LINEAR_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.RAID1_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.RAID4_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.RAID5_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.RAID6_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.RAID10_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.Layout.STRIPED_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.HW_CORES_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.HW_LAN_QUEUES_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.NONPROD_VCORES_VALUE;
import static one.cloud.schedulers.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

final class PlacementCapacity {
    private final PlacementModel model;
    private final int stride;
    private final int[] slot;
    private final boolean[] hasNuma;
    private final boolean[] hasStorage;
    private int[] inventory;
    private double[] capacity;
    private double[] used;
    private boolean[] pristine;

    PlacementCapacity(PlacementModel model) {
        this(model, true);
    }

    PlacementCapacity(PlacementModel model, boolean complete) {
        this.model = model;
        stride = model.dimensionCount + 2;
        slot = new int[model.alloc.length];
        hasNuma = new boolean[model.hostCount + 1];
        hasStorage = new boolean[hasNuma.length];
        Arrays.fill(slot, -1);
        for (int resource = 1; resource < slot.length; resource++) {
            final int profile = model.constraints[resource];
            if (model.rules.kind[profile] == 2) {
                hasNuma[model.rules.parent[profile]] = true;
            } else if (model.rules.kind[profile] == 3
                    && (hardwareMedia(model.rules.media[profile]) || hardwareMedia(model.rules.alternativeMedia[profile]))) {
                hasStorage[model.rules.parent[profile]] = true;
            }
        }
        inventory = complete ? new int[inventoryCount()] : new int[0];
        if (complete) {
            int next = 0;
            for (int resource = 1; resource < slot.length; resource++) {
                if (inventory(resource)) {
                    inventory[next++] = resource;
                }
            }
        }
        resize(inventory.length);
        for (int index = 0; index < inventory.length; index++) {
            final int resource = inventory[index];
            slot[resource] = index;
            for (int dimension = 1; dimension <= model.dimensionCount; dimension++) {
                capacity[index * stride + dimension] = supply(resource, dimension);
            }
            capacity[index * stride + model.dimensionCount + 1] = model.rules.alternativeSpindles[model.constraints[resource]];
            pristine[index] = true;
        }
        if (complete) {
            for (int host = 1; host < hasStorage.length; host++) {
                if (hasStorage[host]) {
                    Arrays.fill(capacity, row(host) * stride + 5, row(host) * stride + 11, 0);
                }
            }
            for (final int resource : inventory) {
                if (model.rules.kind[model.constraints[resource]] == 3) {
                    project(resource, 1);
                }
            }
        }
    }

    void load(PlacementContext context, int[] resources) {
        for (final int resource : inventory) {
            slot[resource] = -1;
        }
        inventory = resources;
        resize(resources.length);
        Arrays.fill(used, 0, resources.length * stride, 0);
        for (int index = 0; index < resources.length; index++) {
            final int resource = resources[index];
            slot[resource] = index;
            final int profile = model.constraints[resource];
            boolean empty = true;
            for (int dimension = 1; dimension <= model.dimensionCount; dimension++) {
                final double value = context.available(resource, dimension);
                capacity[index * stride + dimension] = value;
                empty &= value == supply(resource, dimension);
            }
            final double alternative = model.rules.alternativeMedia[profile] == 0 ? 0
                    : context.available(resource, 19, model.rules.alternativeMedia[profile]);
            capacity[index * stride + model.dimensionCount + 1] = alternative;
            pristine[index] = empty && alternative == model.rules.alternativeSpindles[profile];
        }
    }

    double available(int resource, int dimension) {
        return available(resource, dimension, 0);
    }

    double available(int resource, int dimension, int media) {
        final int row = row(resource);
        if (dimension <= 0 || dimension > model.dimensionCount) {
            throw new IllegalArgumentException("Invalid capacity dimension");
        }
        final int profile = model.constraints[resource];
        final boolean alternative = media != 0 && media == model.rules.alternativeMedia[profile];
        if (media != 0 && !alternative && media != model.rules.media[profile]) {
            throw new IllegalArgumentException("Media is not a device view");
        }
        int offset = row * stride + dimension;
        if (dimension == 19) {
            final int other = row * stride + (alternative ? 19 : model.dimensionCount + 1);
            if (used[other] > 0) {
                return 0;
            }
            if (alternative) {
                offset = row * stride + model.dimensionCount + 1;
            }
        }
        return Math.max(0, capacity[offset] - used[offset]);
    }

    Debit prepare(Placement... plans) {
        return prepare(plans, new Debit());
    }

    Debit prepare(Placement[] plans, Debit target) {
        return prepare(plans, plans.length, target);
    }

    Debit prepare(Placement[] plans, int count, Debit target) {
        target.reset();
        for (int index = 0; index < count; index++) {
            final var plan = plans[index];
            if (plan == null) {
                throw new IllegalArgumentException("Placement cannot be null");
            }
            validate(plan.resource(), plan.host(), plan.numa(), plan.diskOffsets(), plan.devices(), plan.devices().length, Integer.MAX_VALUE);
        }
        for (int index = 0; index < count; index++) {
            final var plan = plans[index];
            if (!add(plan.resource(), plan.host(), plan.numa(), plan.diskOffsets(), plan.devices(), target, Integer.MAX_VALUE)) {
                return null;
            }
        }
        return check(target);
    }

    Debit preparePartial(int resource, int host, int numa, int[] offsets, int[] devices, int deviceCount, int disks, Debit target) {
        target.reset();
        validate(resource, host, numa, offsets, devices, deviceCount, disks);
        return add(resource, host, numa, offsets, devices, target, disks) ? check(target) : null;
    }

    private Debit check(Debit debit) {
        for (int index = 0; index < debit.size; index++) {
            final int offset = debit.index[index];
            final int resource = inventory[offset / stride];
            final int dimension = offset % stride;
            if (resource <= model.hostCount && hasStorage[resource] && dimension >= 5 && dimension <= 10) {
                debit.project(offset, 0);
            } else if (!fits(used[offset] + debit.amount[index], capacity[offset])) {
                return null;
            }
        }
        for (int next = 0; next < debit.deviceCount; next++) {
            final int resource = debit.devices[next];
            final int offset = row(resource) * stride;
            if (used[offset + 19] + debit.get(offset + 19) > 0
                    && used[offset + model.dimensionCount + 1] + debit.get(offset + model.dimensionCount + 1) > 0) {
                return null;
            }
            project(debit, resource);
        }
        for (int index = 0; index < debit.projectionSize; index++) {
            final int offset = debit.projectionIndex[index];
            if (!fits(used[offset] + debit.get(offset), capacity[offset] + debit.projectionAmount[index])) {
                return null;
            }
        }
        return debit;
    }

    void allocate(Debit debit) {
        update(debit, 1);
    }

    void release(Debit debit) {
        update(debit, -1);
    }

    private void update(Debit debit, int sign) {
        for (int index = 0; index < debit.deviceCount; index++) {
            project(debit.devices[index], -1);
        }
        for (int index = 0; index < debit.size; index++) {
            final int offset = debit.index[index];
            used[offset] = Math.max(0, used[offset] + sign * debit.amount[index]);
        }
        for (int index = 0; index < debit.deviceCount; index++) {
            project(debit.devices[index], 1);
        }
    }

    private void project(int resource, int sign) {
        final int profile = model.constraints[resource];
        if ((model.rules.flags[profile] & 1) != 0) {
            return;
        }
        final int host = model.rules.parent[profile];
        project(host, model.rules.media[profile], sign * available(resource, 18), sign * available(resource, 19));
        if (model.rules.alternativeMedia[profile] != 0) {
            project(host, model.rules.alternativeMedia[profile], sign * available(resource, 18),
                    sign * available(resource, 19, model.rules.alternativeMedia[profile]));
        }
    }

    private void project(int host, int media, double extents, double spindles) {
        if (hardwareMedia(media)) {
            final int dimension = media == 1 ? 9 : media == 2 ? 7 : 5;
            capacity[row(host) * stride + dimension] += extents * (model.extentBytes / 1073741824.0);
            capacity[row(host) * stride + dimension + 1] += spindles;
        }
    }

    private void project(Debit debit, int resource) {
        final int profile = model.constraints[resource];
        if ((model.rules.flags[profile] & 1) != 0) {
            return;
        }
        final int offset = row(resource) * stride;
        final int primary = 19;
        final int alternative = model.dimensionCount + 1;
        final double remainingPrimary = used[offset + alternative] + debit.get(offset + alternative) > 0 ? 0
                : capacity[offset + primary] - used[offset + primary] - debit.get(offset + primary);
        final double remainingAlternative = used[offset + primary] + debit.get(offset + primary) > 0 ? 0
                : capacity[offset + alternative] - used[offset + alternative] - debit.get(offset + alternative);
        final int host = model.rules.parent[profile];
        project(debit, host, model.rules.media[profile], -debit.get(offset + 18),
                remainingPrimary - available(resource, 19));
        if (model.rules.alternativeMedia[profile] != 0) {
            project(debit, host, model.rules.alternativeMedia[profile], -debit.get(offset + 18),
                    remainingAlternative - available(resource, 19, model.rules.alternativeMedia[profile]));
        }
    }

    private void project(Debit debit, int host, int media, double extents, double spindles) {
        if (hardwareMedia(media)) {
            final int dimension = media == 1 ? 9 : media == 2 ? 7 : 5;
            debit.project(row(host) * stride + dimension, extents * (model.extentBytes / 1073741824.0));
            debit.project(row(host) * stride + dimension + 1, spindles);
        }
    }

    int[] changedHosts(Debit debit) {
        final int[] hosts = new int[debit.size];
        int size = 0;
        for (int index = 0; index < debit.size; index++) {
            final int resource = inventory[debit.index[index] / stride];
            final int profile = model.constraints[resource];
            final int host = model.rules.kind[profile] == 1 ? resource : model.rules.parent[profile];
            int next = 0;
            while (next < size && hosts[next] != host) {
                next++;
            }
            if (next == size) {
                hosts[size++] = host;
            }
        }
        return Arrays.copyOf(hosts, size);
    }

    boolean wholeAvailable(int resource) {
        final int row = row(resource);
        final int offset = row * stride;
        return pristine[row] && used[offset + 18] == 0 && used[offset + 19] == 0 && used[offset + 20] == 0
                && used[offset + model.dimensionCount + 1] == 0;
    }

    private boolean add(int resource, int host, int numa, int[] offsets, int[] devices, Debit debit, int completed) {
        final int profile = model.constraints[resource];
        final int hostProfile = model.constraints[host];
        if ((model.rules.flags[hostProfile] & 1) != 0) {
            return false;
        }
        row(host);
        if (numa != 0) {
            row(numa);
        }
        final int firstDisk = model.rules.diskOffset[profile];
        final int lastDisk = model.rules.diskOffset[profile + 1];
        final int allocation = model.alloc[resource];
        double cores = 0;
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            if (model.allocations.type[index] == 15) {
                cores = model.allocations.amount[index];
            }
        }
        boolean vcores = false;
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            final int dimension = model.allocations.type[index];
            if (firstDisk != lastDisk && model.rules.kind[profile] == 5 && (dimension == 2 || dimension >= 5 && dimension <= 10)) {
                continue;
            }
            final double amount = dimension == 1 && cores > 0 ? cores * model.rules.cpuPower[hostProfile]
                    : model.allocations.amount[index];
            vcores |= dimension == 1;
            if (!addScalar(debit, host, numa, dimension, amount)) {
                return false;
            }
        }
        if (cores > 0 && !vcores && !addScalar(debit, host, numa, 1, cores * model.rules.cpuPower[hostProfile])) {
            return false;
        }
        for (int disk = firstDisk; disk < lastDisk && disk - firstDisk < completed; disk++) {
            final int begin = offsets[disk - firstDisk];
            final int end = offsets[disk - firstDisk + 1];
            if (!addDisk(debit, host, numa, devices, disk, begin, end)) {
                return false;
            }
        }
        return true;
    }

    private boolean addScalar(Debit debit, int host, int numa, int dimension, double amount) {
        debit.add(row(host) * stride + dimension, amount);
        if (numaAware(dimension) && amount > 0) {
            if (numa != 0) {
                debit.add(row(numa) * stride + dimension, amount);
            } else if (hasNuma[host]) {
                return false;
            }
        }
        return true;
    }

    private boolean addDisk(Debit debit, int host, int numa, int[] devices, int disk, int begin, int end) {
        final var rules = model.rules;
        final int count = end - begin;
        if (count == 0 && (rules.diskFlags[disk] & 2) != 0) {
            return true;
        }
        final int stripes = stripes(rules.diskLayout[disk], count);
        final long extents = Math.addExact(rules.diskExtents[disk] / stripes
                + (rules.diskExtents[disk] % stripes == 0 ? 0 : 1), rules.metadataExtents[disk]);
        final double spindles = Math.ceil(rules.diskSpindles[disk] / stripes);
        final double iops = Double.isNaN(rules.diskIops[disk]) ? 0
                : Math.min(rules.diskIops[disk] / stripes, Double.isNaN(rules.diskIopsCap[disk])
                        ? Double.POSITIVE_INFINITY : rules.diskIopsCap[disk]);
        final int media = rules.diskMedia[disk];
        if (media == 5) {
            return false;
        }
        for (int index = begin; index < end; index++) {
            final int device = devices[index];
            final int profile = model.constraints[device];
            final boolean alternative = rules.media[profile] != media;
            if (alternative && (media != 2 || rules.alternativeMedia[profile] != media
                    || reserved(rules.diskLayout[disk]) || count != 1 || (rules.diskFlags[disk] & 1) != 0)) {
                return false;
            }
            if ((rules.flags[profile] & 1) != 0) {
                return false;
            }
            if (media == 4 && rules.ordinal[profile] != 0
                    && (numa == 0 || rules.ordinal[model.constraints[numa]] != rules.ordinal[profile])) {
                return false;
            }
            final int row = row(device);
            final int offset = row * stride;
            double consumedExtents = extents;
            double consumedSpindles = spindles;
            double consumedIops = iops;
            if ((rules.diskFlags[disk] & 1) != 0) {
                if (!pristine[row] || used[offset + 18] + debit.get(offset + 18) > 0
                        || used[offset + 19] + debit.get(offset + 19) > 0
                        || used[offset + 20] + debit.get(offset + 20) > 0
                        || used[offset + model.dimensionCount + 1] + debit.get(offset + model.dimensionCount + 1) > 0) {
                    return false;
                }
                consumedExtents = capacity[offset + 18];
                consumedSpindles = capacity[offset + 19];
                consumedIops = capacity[offset + 20];
                if (consumedExtents < extents || consumedSpindles < spindles || consumedIops < iops) {
                    return false;
                }
            }
            debit.device(device);
            debit.add(offset + 18, consumedExtents);
            debit.add(offset + (alternative ? model.dimensionCount + 1 : 19), consumedSpindles);
            debit.add(offset + 20, consumedIops);
            if (media == 4) {
                if (!addScalar(debit, host, numa, 2, consumedExtents * (model.extentBytes / 1048576.0))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void validate(int resource, int host, int numa, int[] offsets, int[] devices, int deviceCount, int completed) {
        if (resource <= 0 || resource >= model.alloc.length
                || host <= 0 || host > model.hostCount || numa < 0) {
            throw new IllegalArgumentException("Invalid placement resource or host");
        }
        final int profile = model.constraints[resource];
        if (model.rules.kind[profile] != 4 && model.rules.kind[profile] != 5) {
            throw new IllegalArgumentException("Only requests can be placed");
        }
        if (numa != 0 && (numa >= model.constraints.length
                || model.rules.kind[model.constraints[numa]] != 2
                || model.rules.parent[model.constraints[numa]] != host)) {
            throw new IllegalArgumentException("NUMA does not belong to host");
        }
        final int first = model.rules.diskOffset[profile];
        final int last = model.rules.diskOffset[profile + 1];
        if (offsets.length < last - first + 1 || completed == Integer.MAX_VALUE && offsets.length != last - first + 1
                || offsets[0] != 0 || offsets[last - first] != deviceCount) {
            throw new IllegalArgumentException("Disk plan offsets differ from request");
        }
        for (int disk = first; disk < last && disk - first < completed; disk++) {
            final int begin = offsets[disk - first];
            final int end = offsets[disk - first + 1];
            final int count = end - begin;
            if (begin < 0 || end < begin || end > deviceCount) {
                throw new IllegalArgumentException("Invalid disk offsets");
            }
            if (count == 0 && (model.rules.diskFlags[disk] & 2) != 0) {
                continue;
            }
            final int stripes = stripes(model.rules.diskLayout[disk], count);
            final int firstDevice = count == 1 && begin >= 0 && begin < deviceCount ? devices[begin] : 0;
            final boolean alternative = firstDevice > 0 && firstDevice < model.constraints.length
                    && model.rules.diskMedia[disk] == 2 && model.rules.media[model.constraints[firstDevice]] != 2
                    && model.rules.alternativeMedia[model.constraints[firstDevice]] == 2
                    && !reserved(model.rules.diskLayout[disk]) && (model.rules.diskFlags[disk] & 1) == 0;
            if (count == 0 || !alternative && (count < model.rules.minDevices[disk] || count > model.rules.maxDevices[disk])
                    || stripes < model.rules.minStripes[disk] || stripes > model.rules.maxStripes[disk]) {
                throw new IllegalArgumentException("Invalid device or stripe count");
            }
            for (int index = begin; index < end; index++) {
                final int device = devices[index];
                if (device <= 0 || device >= model.constraints.length
                        || model.rules.kind[model.constraints[device]] != 3
                        || model.rules.parent[model.constraints[device]] != host) {
                    throw new IllegalArgumentException("Device does not belong to host");
                }
                for (int previous = begin; previous < index; previous++) {
                    if (devices[previous] == device) {
                        throw new IllegalArgumentException("A volume cannot repeat a device");
                    }
                }
            }
        }
    }

    static int stripes(int layout, int devices) {
        return switch (layout) {
            case AUTO_VALUE, LINEAR_VALUE, STRIPED_VALUE -> devices;
            case RAID1_VALUE -> devices == 2 ? 1 : 0;
            case RAID4_VALUE, RAID5_VALUE -> devices - 1;
            case RAID6_VALUE -> devices - 2;
            case RAID10_VALUE -> devices % 2 == 0 ? devices / 2 : 0;
            default -> 0;
        };
    }

    static boolean reserved(int layout) {
        return layout >= RAID1_VALUE;
    }

    static boolean numaAware(int dimension) {
        return dimension == VCORES_VALUE || dimension == MEM_MIB_VALUE || dimension == NONPROD_VCORES_VALUE
                || dimension == HW_CORES_VALUE || dimension == HW_LAN_QUEUES_VALUE;
    }

    private static boolean fits(double demand, double limit) {
        return Double.isFinite(demand) && demand <= limit + Math.ulp(limit) * 4;
    }

    private static boolean hardwareMedia(int media) {
        return media >= 1 && media <= 3;
    }

    private double supply(int resource, int dimension) {
        final int profile = model.alloc[resource];
        for (int index = model.allocations.offset[profile]; index < model.allocations.offset[profile + 1]; index++) {
            if (model.allocations.type[index] == dimension) {
                return model.allocations.amount[index];
            }
        }
        return 0;
    }

    private int row(int resource) {
        if (resource <= 0 || resource >= slot.length || slot[resource] < 0) {
            throw new IllegalArgumentException("Resource is not loaded inventory");
        }
        return slot[resource];
    }

    private boolean inventory(int resource) {
        final int kind = model.rules.kind[model.constraints[resource]];
        return kind >= 1 && kind <= 3;
    }

    private int inventoryCount() {
        int count = 0;
        for (int resource = 1; resource < slot.length; resource++) {
            count += inventory(resource) ? 1 : 0;
        }
        return count;
    }

    private void resize(int count) {
        if (capacity == null || capacity.length < count * stride) {
            capacity = new double[Math.multiplyExact(count, stride)];
            used = new double[capacity.length];
            pristine = new boolean[count];
        }
    }

    static final class Debit {
        private int[] index = new int[16];
        private double[] amount = new double[16];
        private int size;
        private int[] devices = new int[4];
        private int deviceCount;
        private int[] projectionIndex = new int[8];
        private double[] projectionAmount = new double[8];
        private int projectionSize;

        private void reset() {
            size = 0;
            deviceCount = 0;
            projectionSize = 0;
        }

        private void add(int offset, double value) {
            if (value == 0) {
                return;
            }
            for (int next = 0; next < size; next++) {
                if (index[next] == offset) {
                    amount[next] += value;
                    return;
                }
            }
            if (size == index.length) {
                index = Arrays.copyOf(index, size * 2);
                amount = Arrays.copyOf(amount, size * 2);
            }
            index[size] = offset;
            amount[size++] = value;
        }

        private double get(int offset) {
            for (int next = 0; next < size; next++) {
                if (index[next] == offset) {
                    return amount[next];
                }
            }
            return 0;
        }

        private void device(int resource) {
            for (int index = 0; index < deviceCount; index++) {
                if (devices[index] == resource) {
                    return;
                }
            }
            if (deviceCount == devices.length) {
                devices = Arrays.copyOf(devices, devices.length * 2);
            }
            devices[deviceCount++] = resource;
        }

        private void project(int offset, double value) {
            for (int next = 0; next < projectionSize; next++) {
                if (projectionIndex[next] == offset) {
                    projectionAmount[next] += value;
                    return;
                }
            }
            if (projectionSize == projectionIndex.length) {
                projectionIndex = Arrays.copyOf(projectionIndex, projectionSize * 2);
                projectionAmount = Arrays.copyOf(projectionAmount, projectionSize * 2);
            }
            projectionIndex[projectionSize] = offset;
            projectionAmount[projectionSize++] = value;
        }

    }
}
