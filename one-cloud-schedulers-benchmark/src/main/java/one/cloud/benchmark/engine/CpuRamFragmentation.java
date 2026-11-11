package one.cloud.benchmark.engine;

import java.util.LinkedHashMap;
import java.util.Objects;

import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Media.MEMDISK_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.HW_CORES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

final class CpuRamFragmentation {
    private final ClusterSpec model;
    private final Sample[] samples;
    private final double cpuScale, ramScale;

    CpuRamFragmentation(ClusterSpec model, Request[] catalogue, double maxCpu, double maxRam) {
        this.model = model;
        cpuScale = maxCpu > 0 ? maxCpu : 1;
        ramScale = maxRam > 0 ? maxRam : 1;
        final var frequencies = new LinkedHashMap<Profile, Integer>();
        for (final var request : Objects.requireNonNull(catalogue)) {
            for (int member = 0; member < Objects.requireNonNull(request).size(); member++) {
                final var profile = profile(request.resource(member));
                if (profile.vcores > 0 || profile.hardware > 0 || profile.ram > 0) {
                    frequencies.merge(profile, 1, Math::addExact);
                }
            }
        }
        samples = frequencies.entrySet().stream().map(entry -> new Sample(entry.getKey(), entry.getValue()))
                .toArray(Sample[]::new);
    }

    private Profile profile(int resource) {
        if (resource >= model.alloc.length) {
            throw new IllegalArgumentException("Invalid catalogue resource");
        }
        final var rules = model.rules;
        final int rule = model.constraints[resource];
        final int kind = rules.kind[rule];
        if (kind != POD_VALUE && kind != SHARD_VALUE) {
            throw new IllegalArgumentException("Catalogue members must be workload resources");
        }
        final int firstDisk = rules.diskOffset[rule];
        final int lastDisk = rules.diskOffset[rule + 1];
        final var allocations = model.allocations;
        final int allocation = model.alloc[resource];
        double vcores = 0, hardware = 0, ram = 0;
        for (int index = allocations.offset[allocation]; index < allocations.offset[allocation + 1]; index++) {
            final double amount = allocations.amount[index];
            if (amount == 0) {
                continue;
            }
            switch (allocations.type[index]) {
                case VCORES_VALUE -> vcores = amount;
                case HW_CORES_VALUE -> hardware = amount;
                case MEM_MIB_VALUE -> {
                    if (kind != SHARD_VALUE || firstDisk == lastDisk) {
                        ram = amount;
                    }
                }
                default -> {
                }
            }
        }
        for (int disk = firstDisk; disk < lastDisk; disk++) {
            if (rules.diskMedia[disk] == MEMDISK_VALUE && (rules.diskFlags[disk] & 2) == 0) {
                ram += rules.diskExtents[disk] * (model.extentBytes / 1048576.0);
            }
        }
        return new Profile(hardware > 0 ? 0 : vcores, hardware, ram);
    }

    double amount(CapacityLedger capacity, int host) {
        if (samples.length == 0) {
            return 0;
        }
        final double cpu = capacity.available(host, VCORES_VALUE);
        final double ram = model.dimensionCount >= MEM_MIB_VALUE ? capacity.available(host, MEM_MIB_VALUE) : 0;
        final double power = model.rules.cpuPower[model.constraints[host]];
        double amount = 0;
        for (final var sample : samples) {
            final var profile = sample.profile;
            final double cpuDemand = profile.hardware > 0 ? profile.hardware * power : profile.vcores;
            final double ramDemand = profile.ram;
            final double excess;
            if (cpuDemand == 0) {
                excess = ramDemand > 0 ? cpu / cpuScale : 0;
            } else if (ramDemand == 0) {
                excess = ram / ramScale;
            } else if (cpuDemand >= ramDemand) {
                excess = excess(cpu, ram, cpuDemand, ramDemand, cpuScale, ramScale);
            } else {
                excess = excess(ram, cpu, ramDemand, cpuDemand, ramScale, cpuScale);
            }
            amount += sample.frequency * excess;
        }
        return amount;
    }

    private static double excess(double first, double second, double firstDemand, double secondDemand,
            double firstScale, double secondScale) {
        if (first == 0) {
            return second / secondScale;
        }
        if (second == 0) {
            return first / firstScale;
        }
        // Pair on the larger demand first to keep the ratio bounded, even for tiny profiles.
        final double ratio = secondDemand / firstDemand;
        final double pairedSecond = ratio >= Double.MIN_NORMAL ? first * ratio : first / firstDemand * secondDemand;
        if (pairedSecond <= second) {
            return (second - pairedSecond) / secondScale;
        }
        final double pairedFirst = ratio >= Double.MIN_NORMAL ? second / ratio : second / secondDemand * firstDemand;
        return Math.max(0, first - pairedFirst) / firstScale;
    }

    private record Profile(double vcores, double hardware, double ram) {
    }

    private record Sample(Profile profile, int frequency) {
    }
}
