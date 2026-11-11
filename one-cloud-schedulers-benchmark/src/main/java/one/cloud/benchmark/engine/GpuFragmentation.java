package one.cloud.benchmark.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.GpuTopology;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;

final class GpuFragmentation {
    private final ClusterSpec model;
    private final Sample[] samples;
    private final int[][] histogram = new int[2][GpuTopology.SHARES + 1];
    private final long[][] fragmented = new long[2][GpuTopology.SHARES + 1];
    private final long[] free = new long[2];
    private final int[] maximum = new int[2];

    GpuFragmentation(ClusterSpec model, Request[] catalogue) {
        this.model = model;
        if (model.gpus == null) {
            throw new IllegalArgumentException("GPU fragmentation requires the devices20 model");
        }
        final var frequencies = new LinkedHashMap<Profile, Integer>();
        for (final var request : Objects.requireNonNull(catalogue)) {
            for (int member = 0; member < Objects.requireNonNull(request).size(); member++) {
                final int resource = request.resource(member);
                if (resource >= model.alloc.length) {
                    throw new IllegalArgumentException("Invalid catalogue resource");
                }
                final int kind = model.rules.kind[model.constraints[resource]];
                if (kind != POD_VALUE && kind != SHARD_VALUE) {
                    throw new IllegalArgumentException("Catalogue members must be workload resources");
                }
                final int nvidia = model.gpus.demand(resource, 11);
                final int intel = model.gpus.demand(resource, 17);
                double vcores = 0, nonprod = 0, hardware = 0;
                if (nvidia != 0 || intel != 0) {
                    final int allocation = model.alloc[resource];
                    for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
                        switch (model.allocations.type[index]) {
                            case 1 -> vcores = model.allocations.amount[index];
                            case 12 -> nonprod = model.allocations.amount[index];
                            case 15 -> hardware = model.allocations.amount[index];
                        }
                    }
                }
                final var profile = new Profile(vcores, nonprod, hardware, nvidia, intel);
                frequencies.merge(profile, 1, Math::addExact);
            }
        }
        samples = frequencies.entrySet().stream().map(entry -> new Sample(entry.getKey(), entry.getValue()))
                .toArray(Sample[]::new);
    }

    double amount(CapacityLedger capacity, int host) {
        if (samples.length == 0) {
            return 0;
        }
        for (int type = 0; type < histogram.length; type++) {
            Arrays.fill(histogram[type], 0);
            free[type] = 0;
            maximum[type] = 0;
            final int dimension = type == 0 ? 11 : 17;
            for (int device = model.gpus.first(host, dimension); device < model.gpus.end(host, dimension); device++) {
                final int remaining = capacity.gpuAvailable(device);
                histogram[type][remaining]++;
                free[type] += remaining;
                maximum[type] = Math.max(maximum[type], remaining);
            }
            fragmented[type][0] = 0;
            for (int threshold = 1; threshold <= GpuTopology.SHARES; threshold++) {
                fragmented[type][threshold] = fragmented[type][threshold - 1]
                        + (long) (threshold - 1) * histogram[type][threshold - 1];
            }
        }
        final long total = free[0] + free[1];
        if (total == 0) {
            return 0;
        }
        double amount = 0;
        for (final var sample : samples) {
            final var profile = sample.profile;
            if (profile.nvidia == 0 && profile.intel == 0 || !cpuFits(capacity, host, profile)
                    || !gpuFits(0, profile.nvidia) || !gpuFits(1, profile.intel)) {
                amount += sample.frequency * (double) total;
            } else {
                final long nvidia = profile.nvidia == 0 ? free[0] : fragmented[0][Math.min(profile.nvidia, GpuTopology.SHARES)];
                final long intel = profile.intel == 0 ? free[1] : fragmented[1][Math.min(profile.intel, GpuTopology.SHARES)];
                amount += sample.frequency * (double) (nvidia + intel);
            }
        }
        return amount;
    }

    private boolean cpuFits(CapacityLedger capacity, int host, Profile profile) {
        final double vcores = profile.hardware > 0
                ? profile.hardware * model.rules.cpuPower[model.constraints[host]] : profile.vcores;
        return fits(capacity, host, 1, vcores) && fits(capacity, host, 12, profile.nonprod)
                && fits(capacity, host, 15, profile.hardware);
    }

    private static boolean fits(CapacityLedger capacity, int host, int dimension, double demand) {
        if (demand == 0) {
            return true;
        }
        final double available = capacity.available(host, dimension);
        return Double.isFinite(demand) && demand <= available + 4 * Math.ulp(available);
    }

    private boolean gpuFits(int type, int demand) {
        return demand == 0 || (demand < GpuTopology.SHARES ? maximum[type] >= demand
                : histogram[type][GpuTopology.SHARES] >= demand / GpuTopology.SHARES);
    }

    private record Profile(double vcores, double nonprod, double hardware, int nvidia, int intel) {
    }

    private record Sample(Profile profile, int frequency) {
    }
}
