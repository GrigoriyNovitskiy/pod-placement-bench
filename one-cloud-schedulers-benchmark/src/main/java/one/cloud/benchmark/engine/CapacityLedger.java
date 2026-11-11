package one.cloud.benchmark.engine;

import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntToDoubleFunction;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;

import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.GpuTopology;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.AUTO_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.LINEAR_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.RAID1_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.RAID4_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.RAID5_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.RAID6_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.RAID10_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout.STRIPED_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.DEVICE_IOPS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.HW_CORES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.HW_LAN_QUEUES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.INTEL_GPUS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.NVIDIA_GPUS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.NONPROD_VCORES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

final class CapacityLedger {
    private static final int[] NO_GPUS = new int[0];
    private static final int CPU_FAMILY = 0, RAM_FAMILY = 1, NETWORK_OUT_FAMILY = 2, NETWORK_IN_FAMILY = 3;
    private static final int QUEUES_FAMILY = 4, NVIDIA_FAMILY = 5, INTEL_FAMILY = 6, SPACE_FAMILY = 7;
    private static final int MEDIA_COUNT = 6;
    private static final int SPINDLES_FAMILY = SPACE_FAMILY + MEDIA_COUNT;
    private static final int IOPS_FAMILY = SPINDLES_FAMILY + MEDIA_COUNT;

    private final ClusterSpec model;
    private final int stride;
    private final int[] slot;
    private final boolean[] hasNuma;
    private final boolean[] hasStorage;
    private int[] inventory;
    private double[] capacity;
    private double[] used;
    private boolean[] pristine;
    private int[] gpuRemaining = NO_GPUS;
    private int gpuFirst, gpuEnd;

    CapacityLedger(ClusterSpec model) {
        this(model, true);
    }

    CapacityLedger(ClusterSpec model, boolean complete) {
        this.model = model;
        if (complete && model.gpus != null) {
            gpuFirst = 1;
            gpuEnd = model.gpus.size() + 1;
            gpuRemaining = new int[model.gpus.size()];
            Arrays.fill(gpuRemaining, GpuTopology.SHARES);
        }
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

    void load(SchedulingContext context, int[] resources) {
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
            if (model.gpus != null && model.rules.kind[profile] == 1) {
                gpuFirst = model.gpus.first(resource, 11);
                gpuEnd = model.gpus.end(resource, 17);
                final int count = gpuEnd - gpuFirst;
                if (gpuRemaining.length < count) {
                    gpuRemaining = new int[count];
                }
                for (int device = gpuFirst; device < gpuEnd; device++) {
                    gpuRemaining[device - gpuFirst] = context.gpuAvailable(device);
                }
            }
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

    ToDoubleFunction<Assignment[]> normalizedResidualL2Scorer(SchedulingContext context, int[][] hostInventory) {
        return resourceFamilyScorer(context, hostInventory, false, false, (remaining, total) -> {
            final double fraction = remaining / total;
            return fraction * fraction;
        }, (before, after) -> {
            double score = 0;
            for (final double value : after) {
                if (!Double.isNaN(value)) {
                    score += value;
                }
            }
            return score;
        });
    }

    ToDoubleFunction<Assignment[]> resourceFamilyScorer(SchedulingContext context, int[][] hostInventory,
            boolean includeHostCpuRam, boolean compareBefore, DoubleBinaryOperator cellScore,
            ToDoubleBiFunction<double[], double[]> combine) {
        final var initial = new CapacityLedger(model);
        final int[] initialSlots = initial.slot;
        final double[] totals = initial.capacity;
        final var debit = new Debit();
        final int families = IOPS_FAMILY + MEDIA_COUNT + Math.max(0, model.dimensionCount - DEVICE_IOPS_VALUE);
        final var scores = new FamilyScores(families, compareBefore, cellScore, combine);
        return plans -> {
            if (plans.length == 0 || plans[0] == null) {
                throw new IllegalArgumentException("A scored plan must be nonempty");
            }
            final int host = plans[0].host();
            if (host <= 0 || host > model.hostCount) {
                throw new IllegalArgumentException("Invalid scored host");
            }
            for (int index = 0; index < plans.length; index++) {
                final var plan = plans[index];
                if (plan == null || plan.host() != host) {
                    throw new IllegalArgumentException("Scored assignments must share one host");
                }
                for (int previous = 0; previous < index; previous++) {
                    if (plans[previous].resource() == plan.resource()) {
                        throw new IllegalArgumentException("Scored resources must be distinct");
                    }
                }
            }
            load(context, hostInventory[host]);
            if (prepare(plans, debit) == null) {
                throw new IllegalArgumentException("Scored assignments exceed physical capacity");
            }
            scores.reset();
            for (int index = 0; index < debit.size; index++) {
                final int offset = debit.index[index];
                final int resource = inventory[offset / stride];
                final int dimension = offset % stride;
                if ((includeHostCpuRam && resource == host && dimension <= MEM_MIB_VALUE)
                        || (model.gpus != null && (dimension == NVIDIA_GPUS_VALUE || dimension == INTEL_GPUS_VALUE))) {
                    continue;
                }
                final double total = totals[initialSlots[resource] * stride + dimension];
                if (dimension == DEVICE_IOPS_VALUE && total == Double.MAX_VALUE) {
                    continue;
                }
                if (total == 0) {
                    throw new IllegalArgumentException("Positive debit against zero initial capacity");
                }
                scores.add(family(resource, dimension), total, capacity[offset], debit.amount[index]);
            }
            if (includeHostCpuRam) {
                for (int dimension = VCORES_VALUE; dimension <= Math.min(MEM_MIB_VALUE, model.dimensionCount); dimension++) {
                    final double total = totals[initialSlots[host] * stride + dimension];
                    if (total > 0) {
                        final int offset = row(host) * stride + dimension;
                        scores.add(family(host, dimension), total, capacity[offset], debit.get(offset));
                    }
                }
            }
            for (int index = 0; index < debit.gpuCount; index++) {
                final int device = debit.gpus[index];
                final int family = model.gpus.type(device) == NVIDIA_GPUS_VALUE ? NVIDIA_FAMILY : INTEL_FAMILY;
                scores.add(family, GpuTopology.SHARES, gpuAvailable(device), debit.gpuShares[index]);
            }
            return scores.finish();
        };
    }

    private static final class FamilyScores {
        private final DoubleBinaryOperator cellScore;
        private final ToDoubleBiFunction<double[], double[]> combine;
        private final double[] before, after;
        private final int[] counts;

        FamilyScores(int families, boolean compareBefore, DoubleBinaryOperator cellScore,
                ToDoubleBiFunction<double[], double[]> combine) {
            this.cellScore = cellScore;
            this.combine = combine;
            before = compareBefore ? new double[families] : null;
            after = new double[families];
            counts = new int[families];
        }

        void reset() {
            Arrays.fill(after, 0);
            Arrays.fill(counts, 0);
            if (before != null) {
                Arrays.fill(before, 0);
            }
        }

        void add(int family, double total, double remaining, double debit) {
            if (before != null) {
                before[family] += cellScore.applyAsDouble(Math.min(total, remaining), total);
            }
            final double residual = Math.max(0, Math.min(total, remaining - debit));
            after[family] += cellScore.applyAsDouble(residual, total);
            counts[family]++;
        }

        double finish() {
            for (int family = 0; family < counts.length; family++) {
                final int count = counts[family];
                after[family] = count == 0 ? Double.NaN : after[family] / count;
                if (before != null) {
                    before[family] = count == 0 ? Double.NaN : before[family] / count;
                }
            }
            return combine.applyAsDouble(before, after);
        }
    }

    ToDoubleFunction<Assignment[]> gpuFragmentationScorer(SchedulingContext context, int[][] hostInventory,
            Request[] catalogue) {
        final var fragmentation = new GpuFragmentation(model, catalogue);
        return fragmentationDeltaScorer(context, hostInventory, host -> fragmentation.amount(this, host));
    }

    ToDoubleFunction<Assignment[]> cpuRamFragmentationScorer(SchedulingContext context, int[][] hostInventory,
            Request[] catalogue) {
        double maxCpu = 0, maxRam = 0;
        for (int host = 1; host <= model.hostCount; host++) {
            maxCpu = Math.max(maxCpu, supply(host, VCORES_VALUE));
            maxRam = Math.max(maxRam, supply(host, MEM_MIB_VALUE));
        }
        final var fragmentation = new CpuRamFragmentation(model, catalogue, maxCpu, maxRam);
        return fragmentationDeltaScorer(context, hostInventory, host -> fragmentation.amount(this, host));
    }

    private ToDoubleFunction<Assignment[]> fragmentationDeltaScorer(SchedulingContext context, int[][] hostInventory,
            IntToDoubleFunction potential) {
        final var debit = new Debit();
        return plans -> {
            if (plans.length == 0 || plans[0] == null) {
                throw new IllegalArgumentException("A scored plan must be nonempty");
            }
            final int host = plans[0].host();
            if (host <= 0 || host > model.hostCount) {
                throw new IllegalArgumentException("Invalid scored host");
            }
            for (int index = 0; index < plans.length; index++) {
                final var plan = plans[index];
                if (plan == null || plan.host() != host) {
                    throw new IllegalArgumentException("Scored assignments must share one host");
                }
                for (int previous = 0; previous < index; previous++) {
                    if (plans[previous].resource() == plan.resource()) {
                        throw new IllegalArgumentException("Scored resources must be distinct");
                    }
                }
            }
            load(context, hostInventory[host]);
            final double before = potential.applyAsDouble(host);
            if (prepare(plans, debit) == null) {
                throw new IllegalArgumentException("Scored assignments exceed physical capacity");
            }
            allocate(debit);
            return potential.applyAsDouble(host) - before;
        };
    }

    private int family(int resource, int dimension) {
        final int profile = model.constraints[resource];
        if (dimension == model.dimensionCount + 1) {
            return SPINDLES_FAMILY + model.rules.alternativeMedia[profile];
        }
        return switch (dimension) {
            case 1, 12, 15 -> CPU_FAMILY;
            case 2 -> RAM_FAMILY;
            case 3, 13 -> NETWORK_OUT_FAMILY;
            case 4, 14 -> NETWORK_IN_FAMILY;
            case 16 -> QUEUES_FAMILY;
            case 11 -> NVIDIA_FAMILY;
            case 17 -> INTEL_FAMILY;
            case 5 -> SPACE_FAMILY + 3;
            case 6 -> SPINDLES_FAMILY + 3;
            case 7 -> SPACE_FAMILY + 2;
            case 8 -> SPINDLES_FAMILY + 2;
            case 9 -> SPACE_FAMILY + 1;
            case 10 -> SPINDLES_FAMILY + 1;
            case 18 -> model.rules.media[profile] == 4 ? RAM_FAMILY : SPACE_FAMILY + model.rules.media[profile];
            case 19 -> SPINDLES_FAMILY + model.rules.media[profile];
            case 20 -> IOPS_FAMILY + model.rules.media[profile];
            default -> IOPS_FAMILY + MEDIA_COUNT + dimension - DEVICE_IOPS_VALUE - 1;
        };
    }

    double available(int resource, int dimension) {
        return available(resource, dimension, 0);
    }

    int gpuAvailable(int device) {
        if (model.gpus == null || device < gpuFirst || device >= gpuEnd) {
            throw new IllegalArgumentException("GPU is not loaded inventory");
        }
        return gpuRemaining[device - gpuFirst];
    }

    boolean selectGpus(int resource, int host, int[] selected) {
        if (model.gpus == null) {
            return selected.length == 0;
        }
        if (selected.length != gpuCount(resource, 11) + gpuCount(resource, 17)) {
            return false;
        }
        int next = 0;
        for (int dimension = 11; dimension <= 17; dimension += 6) {
            final int demand = model.gpus.demand(resource, dimension);
            if (demand == 0) {
                continue;
            }
            final int shares = Math.min(demand, GpuTopology.SHARES);
            final int count = demand < GpuTopology.SHARES ? 1 : demand / GpuTopology.SHARES;
            if (count > model.gpus.end(host, dimension) - model.gpus.first(host, dimension)) {
                return false;
            }
            int best = 0;
            if (shares < GpuTopology.SHARES) {
                for (int device = model.gpus.first(host, dimension); device < model.gpus.end(host, dimension); device++) {
                    final int remaining = gpuAvailable(device);
                    if (remaining >= shares && (best == 0 || remaining < gpuAvailable(best))) {
                        best = device;
                    }
                }
                if (best == 0) {
                    return false;
                }
                selected[next++] = best;
            } else {
                final int end = next + count;
                for (int device = model.gpus.first(host, dimension); device < model.gpus.end(host, dimension) && next < end; device++) {
                    if (gpuAvailable(device) == GpuTopology.SHARES) {
                        selected[next++] = device;
                    }
                }
                if (next != end) {
                    return false;
                }
            }
        }
        return true;
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

    Debit prepare(Assignment... plans) {
        return prepare(plans, new Debit());
    }

    Debit prepare(Assignment[] plans, Debit target) {
        return prepare(plans, plans.length, target);
    }

    Debit prepare(Assignment[] plans, int count, Debit target) {
        target.reset();
        for (int index = 0; index < count; index++) {
            final var plan = plans[index];
            if (plan == null) {
                throw new IllegalArgumentException("Placement cannot be null");
            }
            validate(plan.resource(), plan.host(), plan.numa(), plan.diskOffsets(), plan.devices(), plan.devices().length, Integer.MAX_VALUE);
            validateGpus(plan.resource(), plan.host(), plan.gpus());
        }
        for (int index = 0; index < count; index++) {
            final var plan = plans[index];
            if (!add(plan.resource(), plan.host(), plan.numa(), plan.diskOffsets(), plan.devices(), target, Integer.MAX_VALUE)) {
                return null;
            }
            addGpus(plan.resource(), plan.gpus(), target);
        }
        return check(target);
    }

    Debit preparePartial(int resource, int host, int numa, int[] offsets, int[] devices, int deviceCount, int disks, Debit target) {
        return preparePartial(resource, host, numa, offsets, devices, deviceCount, disks, NO_GPUS, target);
    }

    Debit preparePartial(int resource, int host, int numa, int[] offsets, int[] devices, int deviceCount, int disks,
            int[] gpus, Debit target) {
        target.reset();
        validate(resource, host, numa, offsets, devices, deviceCount, disks);
        validateGpus(resource, host, gpus);
        if (!add(resource, host, numa, offsets, devices, target, disks)) {
            return null;
        }
        addGpus(resource, gpus, target);
        return check(target);
    }

    private void validateGpus(int resource, int host, int[] gpus) {
        if (model.gpus == null) {
            if (gpus.length != 0) {
                throw new IllegalArgumentException("GPU assignments require the devices20 model");
            }
            return;
        }
        int nvidia = 0, intel = 0;
        for (int index = 0; index < gpus.length; index++) {
            final int device = gpus[index];
            if (model.gpus.host(device) != host) {
                throw new IllegalArgumentException("GPU does not belong to host");
            }
            for (int previous = 0; previous < index; previous++) {
                if (gpus[previous] == device) {
                    throw new IllegalArgumentException("A request cannot repeat a GPU");
                }
            }
            if (model.gpus.type(device) == 11) {
                nvidia++;
            } else {
                intel++;
            }
        }
        if (nvidia != gpuCount(resource, 11) || intel != gpuCount(resource, 17)) {
            throw new IllegalArgumentException("GPU assignments differ from request");
        }
    }

    int gpuCount(int resource, int dimension) {
        if (model.gpus == null) {
            return 0;
        }
        final int demand = model.gpus.demand(resource, dimension);
        return demand == 0 ? 0 : demand < GpuTopology.SHARES ? 1 : demand / GpuTopology.SHARES;
    }

    private void addGpus(int resource, int[] gpus, Debit debit) {
        for (final int device : gpus) {
            debit.gpu(device, Math.min(GpuTopology.SHARES, model.gpus.demand(resource, model.gpus.type(device))));
        }
    }

    private Debit check(Debit debit) {
        for (int index = 0; index < debit.gpuCount; index++) {
            if (debit.gpuShares[index] > gpuAvailable(debit.gpus[index])) {
                return null;
            }
        }
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
        for (int index = 0; index < debit.gpuCount; index++) {
            gpuRemaining[debit.gpus[index] - gpuFirst] -= sign * debit.gpuShares[index];
        }
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
            final double amount = model.gpus != null && (dimension == 11 || dimension == 17)
                    ? model.gpus.demand(resource, dimension) / (double) GpuTopology.SHARES
                    : dimension == 1 && cores > 0 ? cores * model.rules.cpuPower[hostProfile]
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
        private int[] gpus = NO_GPUS, gpuShares = NO_GPUS;
        private int gpuCount;
        private int[] index = new int[16];
        private double[] amount = new double[16];
        private int size;
        private int[] devices = new int[4];
        private int deviceCount;
        private int[] projectionIndex = new int[8];
        private double[] projectionAmount = new double[8];
        private int projectionSize;

        private void reset() {
            gpuCount = 0;
            size = 0;
            deviceCount = 0;
            projectionSize = 0;
        }

        private void gpu(int device, int shares) {
            for (int index = 0; index < gpuCount; index++) {
                if (gpus[index] == device) {
                    gpuShares[index] = Math.addExact(gpuShares[index], shares);
                    return;
                }
            }
            if (gpuCount == gpus.length) {
                final int size = Math.max(4, gpuCount * 2);
                gpus = Arrays.copyOf(gpus, size);
                gpuShares = Arrays.copyOf(gpuShares, size);
            }
            gpus[gpuCount] = device;
            gpuShares[gpuCount++] = shares;
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
