package one.cloud.benchmark.model;

import java.util.Objects;

/** Arrays are borrowed. Disk offsets partition disk device IDs; GPU IDs belong to the model's GPU topology. */
public record Assignment(int resource, int host, int numa, int[] diskOffsets, int[] devices, int[] gpus) {
    private static final int[] NO_GPUS = new int[0];

    public Assignment(int resource, int host, int numa, int[] diskOffsets, int[] devices) {
        this(resource, host, numa, diskOffsets, devices, NO_GPUS);
    }

    public Assignment {
        Objects.requireNonNull(diskOffsets);
        Objects.requireNonNull(devices);
        Objects.requireNonNull(gpus);
        if (resource <= 0 || host <= 0 || numa < 0 || diskOffsets.length == 0
                || diskOffsets[0] != 0 || diskOffsets[diskOffsets.length - 1] != devices.length) {
            throw new IllegalArgumentException("Invalid placement IDs or disk offsets");
        }
        for (int index = 1; index < diskOffsets.length; index++) {
            if (diskOffsets[index] < diskOffsets[index - 1]) {
                throw new IllegalArgumentException("Disk offsets must be ordered");
            }
        }
        for (final int device : devices) {
            if (device <= 0) {
                throw new IllegalArgumentException("Device IDs must be positive");
            }
        }
        for (final int gpu : gpus) {
            if (gpu <= 0) {
                throw new IllegalArgumentException("GPU IDs must be positive");
            }
        }
    }

    public Assignment copy() {
        return new Assignment(resource, host, numa, diskOffsets.clone(), devices.clone(), gpus.length == 0 ? NO_GPUS : gpus.clone());
    }
}
