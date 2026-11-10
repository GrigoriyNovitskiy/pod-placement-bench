package one.cloud.benchmark.model;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.POD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.SHARD_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.INTEL_GPUS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.NVIDIA_GPUS_VALUE;

/** Synthetic, equally sized GPU devices derived from aggregate host capacity. IDs start at one. */
public final class GpuTopology {
    public static final int SHARES = 20;

    private final int hostCount;
    private final int[] offsets, hosts, types, allocation, nvidiaDemand, intelDemand;

    GpuTopology(ClusterSpec model) {
        hostCount = model.hostCount;
        allocation = model.alloc;
        final long offsetCount = 2L * (hostCount + 1L) + 1;
        final int profiles = model.allocations.offset.length - 1;
        if (offsetCount > Integer.MAX_VALUE - 8L
                || 4L * offsetCount + 8L * profiles > Runtime.getRuntime().maxMemory() / 4) {
            throw new IllegalArgumentException("GPU topology exceeds the configured heap budget");
        }
        offsets = new int[(int) offsetCount];
        long count = 0;
        for (int host = 1; host <= hostCount; host++) {
            offsets[2 * host] = (int) count + 1;
            count += hostCapacity(model, host, NVIDIA_GPUS_VALUE);
            checkDeviceCount(count, offsetCount, profiles);
            offsets[2 * host + 1] = (int) count + 1;
            count += hostCapacity(model, host, INTEL_GPUS_VALUE);
            checkDeviceCount(count, offsetCount, profiles);
            offsets[2 * host + 2] = (int) count + 1;
        }
        hosts = new int[(int) count + 1];
        types = new int[hosts.length];
        for (int host = 1; host <= hostCount; host++) {
            fill(host, NVIDIA_GPUS_VALUE);
            fill(host, INTEL_GPUS_VALUE);
        }
        nvidiaDemand = new int[profiles];
        intelDemand = new int[profiles];
        final var checked = new boolean[profiles];
        for (int resource = 1; resource < allocation.length; resource++) {
            final int kind = model.rules.kind[model.constraints[resource]];
            final int profile = allocation[resource];
            if ((kind == POD_VALUE || kind == SHARD_VALUE) && !checked[profile]) {
                nvidiaDemand[profile] = requestedShares(amount(model, profile, NVIDIA_GPUS_VALUE));
                intelDemand[profile] = requestedShares(amount(model, profile, INTEL_GPUS_VALUE));
                checked[profile] = true;
            }
        }
    }

    public int size() {
        return hosts.length - 1;
    }

    public int host(int gpu) {
        checkGpu(gpu);
        return hosts[gpu];
    }

    public int type(int gpu) {
        checkGpu(gpu);
        return types[gpu];
    }

    public int first(int host, int dimension) {
        return offsets[index(host, dimension)];
    }

    public int end(int host, int dimension) {
        return offsets[index(host, dimension) + 1];
    }

    public int demand(int resource, int dimension) {
        if (resource <= 0 || resource >= allocation.length) {
            throw new IllegalArgumentException("Invalid GPU request resource");
        }
        return switch (dimension) {
            case NVIDIA_GPUS_VALUE -> nvidiaDemand[allocation[resource]];
            case INTEL_GPUS_VALUE -> intelDemand[allocation[resource]];
            default -> throw new IllegalArgumentException("Invalid GPU dimension");
        };
    }

    private int index(int host, int dimension) {
        if (host <= 0 || host > hostCount) {
            throw new IllegalArgumentException("Invalid GPU host");
        }
        return 2 * host + switch (dimension) {
            case NVIDIA_GPUS_VALUE -> 0;
            case INTEL_GPUS_VALUE -> 1;
            default -> throw new IllegalArgumentException("Invalid GPU dimension");
        };
    }

    private void fill(int host, int dimension) {
        for (int gpu = first(host, dimension); gpu < end(host, dimension); gpu++) {
            hosts[gpu] = host;
            types[gpu] = dimension;
        }
    }

    private void checkGpu(int gpu) {
        if (gpu <= 0 || gpu >= hosts.length) {
            throw new IllegalArgumentException("Invalid GPU device ID");
        }
    }

    private static int hostCapacity(ClusterSpec model, int host, int dimension) {
        return integer(amount(model, model.alloc[host], dimension), "GPU host capacity must be a nonnegative integer");
    }

    private static int requestedShares(double amount) {
        if (amount < 1) {
            final int shares = (int) Math.rint(amount * SHARES);
            final double canonical = shares / (double) SHARES;
            if (!Double.isFinite(amount) || amount < 0
                    || Math.abs(amount - canonical) > 4 * Math.ulp(amount) && amount != (double) (float) canonical) {
                throw new IllegalArgumentException("Fractional GPU demand must be a multiple of 1/20");
            }
            if (amount > 0 && shares == 0) {
                throw new IllegalArgumentException("Positive GPU demand must use at least one share");
            }
            return shares;
        }
        final int devices = integer(amount, "GPU demand of one or more devices must be an integer");
        if (devices > Integer.MAX_VALUE / SHARES) {
            throw new IllegalArgumentException("GPU demand exceeds the supported share count");
        }
        return devices * SHARES;
    }

    private static int integer(double amount, String message) {
        final double rounded = Math.rint(amount);
        if (!Double.isFinite(amount) || amount < 0 || amount > 0 && rounded == 0 || rounded > Integer.MAX_VALUE - 8L
                || Math.abs(amount - rounded) > 4 * Math.ulp(amount)) {
            throw new IllegalArgumentException(message);
        }
        return (int) rounded;
    }

    private static double amount(ClusterSpec model, int profile, int dimension) {
        for (int index = model.allocations.offset[profile]; index < model.allocations.offset[profile + 1]; index++) {
            if (model.allocations.type[index] == dimension) {
                return model.allocations.amount[index];
            }
        }
        return 0;
    }

    private static void checkDeviceCount(long count, long offsetCount, int profiles) {
        if (count > Integer.MAX_VALUE - 9L
                || 8L * (count + 1) + 4L * offsetCount + 9L * profiles > Runtime.getRuntime().maxMemory() / 4) {
            throw new IllegalArgumentException("GPU topology exceeds the configured heap budget");
        }
    }
}
