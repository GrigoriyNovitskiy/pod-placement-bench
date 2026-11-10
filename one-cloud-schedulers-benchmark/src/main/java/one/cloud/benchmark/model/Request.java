package one.cloud.benchmark.model;

import java.util.Arrays;

public final class Request {
    private final int[] resources;

    public Request(int... resources) {
        if (resources.length == 0) {
            throw new IllegalArgumentException("A placement request must have members");
        }
        this.resources = resources.clone();
        final var sorted = this.resources.clone();
        Arrays.sort(sorted);
        for (int index = 0; index < sorted.length; index++) {
            if (sorted[index] <= 0 || index != 0 && sorted[index] == sorted[index - 1]) {
                throw new IllegalArgumentException("Resource IDs must be positive and distinct");
            }
        }
    }

    public int size() {
        return resources.length;
    }

    public int resource(int index) {
        return resources[index];
    }
}
