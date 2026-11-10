package one.cloud.schedulers;

import java.util.Objects;

/**
 * A complete assignment for one resource. NUMA is an inventory resource ID, or zero when absent.
 * Disk offsets partition device IDs by disk plan. Arrays are borrowed and must remain unchanged
 * during a context call; the producer may reuse them after that call returns.
 */
public record Placement(int resource, int host, int numa, int[] diskOffsets, int[] devices) {
    public Placement {
        Objects.requireNonNull(diskOffsets);
        Objects.requireNonNull(devices);
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
    }

    public Placement copy() {
        return new Placement(resource, host, numa, diskOffsets.clone(), devices.clone());
    }
}
