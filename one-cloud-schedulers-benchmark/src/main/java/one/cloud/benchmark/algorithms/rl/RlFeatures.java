package one.cloud.benchmark.algorithms.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import one.cloud.benchmark.algorithms.catboost.CandidateFeatures;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.GpuTopology;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.INTEL_GPUS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.NVIDIA_GPUS_VALUE;

public final class RlFeatures {
    private static final List<String> NAMES = featureNames();
    private final SchedulingContext context;
    private final CandidateFeatures shared;

    public RlFeatures(SchedulingContext context) {
        this.context = context;
        shared = new CandidateFeatures(context);
    }

    public static List<String> names() {
        return NAMES;
    }

    public float[][] extract(Request request, Assignment[][] plans, int count) {
        if (count < 0 || count > plans.length) {
            throw new IllegalArgumentException("Candidate count is out of bounds");
        }
        final var decision = shared.prepare(request, plans, count);
        final var result = new float[decision.rows()][];
        final var gpuByHost = new float[context.model().hostCount + 1][];
        for (int first = 0; first < result.length;) {
            final int rows = Math.min(CandidateFeatures.Decision.MAX_BATCH_ROWS, result.length - first);
            final var batch = decision.extract(first, rows);
            for (int row = 0; row < rows; row++) {
                final int index = first + row;
                final int host = plans[index / request.size()][index % request.size()].host();
                result[index] = appendGpu(batch[row], host, gpuByHost);
            }
            first += rows;
        }
        return result;
    }

    public float[][] extract(Request request, Assignment[][] plans, int count, int[] indices) {
        if (count < 0 || count > plans.length) {
            throw new IllegalArgumentException("Candidate count is out of bounds");
        }
        int previous = -1;
        for (final int index : indices) {
            if (index <= previous || index >= count) {
                throw new IllegalArgumentException("Sparse indices must be increasing and within candidate bounds");
            }
            previous = index;
        }
        final var decision = shared.prepare(request, plans, count);
        final var result = new float[Math.multiplyExact(indices.length, request.size())][];
        final var gpuByHost = new float[context.model().hostCount + 1][];
        int target = 0;
        for (final int candidate : indices) {
            final int firstRow = Math.multiplyExact(candidate, request.size());
            for (int member = 0; member < request.size();) {
                final int rows = Math.min(CandidateFeatures.Decision.MAX_BATCH_ROWS, request.size() - member);
                final var batch = decision.extract(firstRow + member, rows);
                for (int row = 0; row < rows; row++) {
                    final int host = plans[candidate][member + row].host();
                    result[target++] = appendGpu(batch[row], host, gpuByHost);
                }
                member += rows;
            }
        }
        return result;
    }

    private float[] appendGpu(float[] row, int host, float[][] gpuByHost) {
        if (gpuByHost[host] == null) {
            gpuByHost[host] = gpuGeometry(host);
        }
        final var result = Arrays.copyOf(row, NAMES.size());
        System.arraycopy(gpuByHost[host], 0, result, CandidateFeatures.names().size(), 4);
        return result;
    }

    private float[] gpuGeometry(int host) {
        final var result = new float[4];
        final var topology = context.model().gpus;
        if (topology != null) {
            int offset = 0;
            for (int dimension : new int[]{NVIDIA_GPUS_VALUE, INTEL_GPUS_VALUE}) {
                for (int gpu = topology.first(host, dimension); gpu < topology.end(host, dimension); gpu++) {
                    final int free = context.gpuAvailable(gpu);
                    result[offset] = Math.max(result[offset], free / (float) GpuTopology.SHARES);
                    if (free == GpuTopology.SHARES) {
                        result[offset + 1]++;
                    }
                }
                offset += 2;
            }
        }
        return result;
    }

    private static List<String> featureNames() {
        final var names = new ArrayList<>(CandidateFeatures.names());
        names.addAll(List.of("host_nvidia_gpu_max_free_fraction", "host_nvidia_gpu_whole_free_count",
                "host_intel_gpu_max_free_fraction", "host_intel_gpu_whole_free_count"));
        return List.copyOf(names);
    }
}
