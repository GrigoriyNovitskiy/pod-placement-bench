package one.cloud.benchmark.engine;

import java.util.LinkedHashMap;
import java.util.Objects;

import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.HW_CORES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.NONPROD_VCORES_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;

final class CpuFragmentation {
    private final ClusterSpec model;
    private final int[][] numaNodes;
    private final double scale;
    private final Sample[] samples;
    private final double[] vcores, nonprod, hardware;

    CpuFragmentation(ClusterSpec model, int[][] numaNodes, Request[] catalogue, double maxCpu) {
        this.model = model;
        this.numaNodes = numaNodes;
        scale = maxCpu > 0 ? maxCpu : 1;
        int pools = 0;
        for (int host = 1; host <= model.hostCount; host++) {
            pools = Math.max(pools, numaNodes[host].length);
        }
        vcores = new double[pools];
        nonprod = new double[pools];
        hardware = new double[pools];

        final var frequencies = new LinkedHashMap<Profile, Integer>();
        for (final var request : Objects.requireNonNull(catalogue)) {
            for (int member = 0; member < Objects.requireNonNull(request).size(); member++) {
                final var profile = profile(request.resource(member));
                if (profile.vcores > 0 || profile.nonprod > 0 || profile.hardware > 0) {
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
        final int kind = model.rules.kind[model.constraints[resource]];
        if (kind != POD_VALUE && kind != SHARD_VALUE) {
            throw new IllegalArgumentException("Catalogue members must be workload resources");
        }
        final var allocations = model.allocations;
        final int allocation = model.alloc[resource];
        double shared = 0, nonprod = 0, hardware = 0;
        for (int index = allocations.offset[allocation]; index < allocations.offset[allocation + 1]; index++) {
            final double amount = allocations.amount[index];
            if (amount == 0) {
                continue;
            }
            switch (allocations.type[index]) {
                case VCORES_VALUE -> shared = amount;
                case NONPROD_VCORES_VALUE -> nonprod = amount;
                case HW_CORES_VALUE -> hardware = amount;
                default -> {
                }
            }
        }
        return new Profile(hardware > 0 ? 0 : shared, nonprod, hardware);
    }

    double amount(CapacityLedger capacity, int host) {
        if (samples.length == 0) {
            return 0;
        }
        final int[] nodes = numaNodes[host];
        for (int pool = 0; pool < nodes.length; pool++) {
            final int resource = pool == 0 ? host : nodes[pool];
            vcores[pool] = capacity.available(resource, VCORES_VALUE);
            nonprod[pool] = model.dimensionCount >= NONPROD_VCORES_VALUE
                    ? capacity.available(resource, NONPROD_VCORES_VALUE) : 0;
            hardware[pool] = model.dimensionCount >= HW_CORES_VALUE
                    ? capacity.available(resource, HW_CORES_VALUE) : 0;
        }
        final double power = model.rules.cpuPower[model.constraints[host]];
        double amount = 0;
        for (final var sample : samples) {
            final var profile = sample.profile;
            final double cpuDemand = profile.hardware > 0 ? profile.hardware * power : profile.vcores;
            final double freeVcores = cpuDemand > 0 ? vcores[0] : 0;
            final double freeNonprod = profile.nonprod > 0 ? nonprod[0] : 0;
            double usableVcores = 0, usableNonprod = 0;
            if (fits(0, cpuDemand, profile)) {
                if (nodes.length == 1) {
                    continue;
                }
                for (int node = 1; node < nodes.length; node++) {
                    if (fits(node, cpuDemand, profile)) {
                        usableVcores += vcores[node];
                        usableNonprod += nonprod[node];
                    }
                }
            }
            // NUMA balances describe the same resource as the host balance.
            final double strandedVcores = freeVcores - Math.min(freeVcores, usableVcores);
            final double strandedNonprod = freeNonprod - Math.min(freeNonprod, usableNonprod);
            amount += sample.frequency * (strandedVcores / scale + strandedNonprod / scale);
        }
        return amount;
    }

    private boolean fits(int pool, double cpuDemand, Profile profile) {
        return CapacityLedger.fits(cpuDemand, vcores[pool])
                && CapacityLedger.fits(profile.nonprod, nonprod[pool])
                && CapacityLedger.fits(profile.hardware, hardware[pool]);
    }

    private record Profile(double vcores, double nonprod, double hardware) {
    }

    private record Sample(Profile profile, int frequency) {
    }
}
