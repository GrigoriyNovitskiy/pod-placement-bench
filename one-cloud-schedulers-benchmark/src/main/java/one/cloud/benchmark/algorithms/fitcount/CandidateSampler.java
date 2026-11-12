package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Samples planner indices, retaining the compact complement for an optional second draw. */
public final class CandidateSampler {
    private final SplittableRandom random;
    private int[] indices = new int[0];
    private int remaining;

    public CandidateSampler(long seed) {
        random = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
    }

    public int[] sample(int count, int quota) {
        if (count < 0 || quota <= 0) {
            throw new IllegalArgumentException("Candidate count must be nonnegative and quota positive");
        }
        if (indices.length < count) {
            indices = new int[count];
        }
        for (int index = 0; index < count; index++) {
            indices[index] = index;
        }
        remaining = count;
        return draw(quota, random);
    }

    public int[] sampleRemaining(int quota, SplittableRandom auxiliary) {
        if (quota < 0) {
            throw new IllegalArgumentException("Candidate quota must be nonnegative");
        }
        return draw(quota, auxiliary);
    }

    private int[] draw(int quota, SplittableRandom source) {
        final int count = Math.min(quota, remaining);
        final int[] selected = new int[count];
        if (remaining <= quota) {
            System.arraycopy(indices, 0, selected, 0, count);
            remaining = 0;
        } else {
            for (int index = 0; index < count; index++) {
                final int chosen = source.nextInt(remaining);
                selected[index] = indices[chosen];
                indices[chosen] = indices[--remaining];
            }
        }
        Arrays.sort(selected);
        return selected;
    }
}
