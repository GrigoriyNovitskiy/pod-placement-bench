package one.cloud.benchmark.algorithms.catboost;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

public final class CandidateFeatures {
    static final int VERSION = 2;
    private static final long TRAINING_EXTENT_BYTES = 64L << 20;
    private static final int[] DIMENSIONS = {1, 2, 3, 4, 5, 7, 9, 11, 12, 15, 17};
    private static final List<String> NAMES = featureNames();
    private final SchedulingContext context;
    private final double[][] initialHost;
    private final double[] initialTotal = new double[DIMENSIONS.length];

    public CandidateFeatures(SchedulingContext context) {
        this.context = context;
        initialHost = new double[context.model().hostCount + 1][DIMENSIONS.length];
        for (int host = 1; host < initialHost.length; host++) {
            for (int d = 0; d < DIMENSIONS.length; d++) {
                initialHost[host][d] = context.available(host, DIMENSIONS[d]);
                initialTotal[d] += initialHost[host][d];
            }
        }
    }

    public static List<String> names() {
        return NAMES;
    }

    private static List<String> featureNames() {
        var result = new ArrayList<>(List.of("request_kind", "candidate_count", "host_cpu_power",
                "has_numa", "disk_plan_count", "selected_device_count", "device_extents_min",
                "device_iops_log_min_known", "unknown_device_iops_count", "request_link_count",
                "request_hard_limit_count", "request_flags"));
        for (int dimension : DIMENSIONS) {
            String name = ResourceType.forNumber(dimension).name().toLowerCase(Locale.ROOT);
            for (String prefix : List.of("request_", "host_free_", "host_capacity_", "host_free_fraction_",
                    "request_over_host_free_", "anchor_free_", "cluster_free_", "cluster_free_fraction_",
                    "cluster_free_fraction_std_")) {
                result.add(prefix + name);
            }
        }
        return List.copyOf(result);
    }

    public Decision prepare(Request request, Assignment[][] plans, int candidateCount) {
        return new Decision(request, plans, candidateCount);
    }

    public final class Decision {
        public static final int MAX_BATCH_ROWS = 1024;
        private final Request request;
        private final Assignment[][] plans;
        private final int candidateCount;
        private final double[] clusterFree = new double[DIMENSIONS.length];
        private final double[] clusterFreeFraction = new double[DIMENSIONS.length];
        private final double[] clusterFreeFractionStd = new double[DIMENSIONS.length];
        private final double[][] demands;
        private final int[] hardLimitCounts;
        private final ClusterSpec model = context.model();

        private Decision(Request request, Assignment[][] plans, int candidateCount) {
            this.request = request;
            this.plans = plans;
            this.candidateCount = candidateCount;
            double[] fractionSum = new double[DIMENSIONS.length];
            double[] fractionSquareSum = new double[DIMENSIONS.length];
            for (int host = 1; host < initialHost.length; host++) {
                for (int d = 0; d < DIMENSIONS.length; d++) {
                    double free = context.available(host, DIMENSIONS[d]);
                    double fraction = ratio(free, initialHost[host][d]);
                    clusterFree[d] += free;
                    fractionSum[d] += fraction;
                    fractionSquareSum[d] += fraction * fraction;
                }
            }
            for (int d = 0; d < DIMENSIONS.length; d++) {
                double mean = fractionSum[d] / model.hostCount;
                clusterFreeFraction[d] = ratio(clusterFree[d], initialTotal[d]);
                clusterFreeFractionStd[d] = Math.sqrt(Math.max(0, fractionSquareSum[d] / model.hostCount - mean * mean));
            }

            demands = new double[request.size()][model.dimensionCount + 1];
            hardLimitCounts = new int[request.size()];
            for (int member = 0; member < request.size(); member++) {
                final int resource = request.resource(member);
                final int rule = model.constraints[resource];
                final int allocation = model.alloc[resource];
                for (int i = model.allocations.offset[allocation]; i < model.allocations.offset[allocation + 1]; i++) {
                    demands[member][model.allocations.type[i]] = model.allocations.amount[i];
                }
                for (int i = model.rules.limitOffset[rule]; i < model.rules.limitOffset[rule + 1]; i++) {
                    if (model.rules.limitHard[i]) {
                        hardLimitCounts[member]++;
                    }
                }
            }
        }

        public int rows() {
            return Math.multiplyExact(candidateCount, request.size());
        }

        public float[][] extract(int firstRow, int rowCount) {
            if (firstRow < 0 || rowCount < 0 || rowCount > MAX_BATCH_ROWS
                    || firstRow > rows() - rowCount) {
                throw new IllegalArgumentException("Invalid feature batch bounds");
            }
            final var result = new float[rowCount][NAMES.size()];
            for (int row = 0; row < rowCount; row++) {
                final int sourceRow = firstRow + row;
                final int candidate = sourceRow / request.size();
                final int member = sourceRow % request.size();
                final int resource = request.resource(member);
                final var plan = plans[candidate][member];
                if (plan.resource() != resource) {
                    throw new IllegalArgumentException("Candidate member does not match request");
                }
                final int rule = model.constraints[resource];
                final double[] demand = demands[member];
                int host = plan.host();
                int anchor = plan.numa() == 0 ? host : plan.numa();
                double extentMin = Double.POSITIVE_INFINITY;
                double iopsMin = Double.POSITIVE_INFINITY;
                int unknownIops = 0;
                var selectedDevices = new LinkedHashSet<Integer>();
                for (int device : plan.devices()) {
                    selectedDevices.add(device);
                }
                for (int device : selectedDevices) {
                    extentMin = Math.min(extentMin, context.available(device, 18)
                            * (model.extentBytes / (double) TRAINING_EXTENT_BYTES));
                    float iops = logKnownIops(context.available(device, 20));
                    if (Float.isFinite(iops)) {
                        iopsMin = Math.min(iopsMin, iops);
                    } else {
                        unknownIops++;
                    }
                }
                float[] values = result[row];
                int column = 0;
                values[column++] = model.rules.kind[rule];
                values[column++] = candidateCount;
                values[column++] = (float) model.rules.cpuPower[model.constraints[host]];
                values[column++] = plan.numa() == 0 ? 0 : 1;
                values[column++] = model.rules.diskOffset[rule + 1] - model.rules.diskOffset[rule];
                values[column++] = selectedDevices.size();
                values[column++] = (float) (Double.isFinite(extentMin) ? extentMin : 0.0);
                values[column++] = (float) (Double.isFinite(iopsMin) ? iopsMin : 0.0);
                values[column++] = unknownIops;
                values[column++] = model.rules.linkOffset[rule + 1] - model.rules.linkOffset[rule];
                values[column++] = hardLimitCounts[member];
                values[column++] = model.rules.flags[rule];
                for (int d = 0; d < DIMENSIONS.length; d++) {
                    int dimension = DIMENSIONS[d];
                    double free = context.available(host, dimension);
                    values[column++] = (float) demand[dimension];
                    values[column++] = (float) free;
                    values[column++] = (float) initialHost[host][d];
                    values[column++] = (float) ratio(free, initialHost[host][d]);
                    values[column++] = (float) ratio(demand[dimension], free);
                    values[column++] = (float) context.available(anchor, dimension);
                    values[column++] = (float) clusterFree[d];
                    values[column++] = (float) clusterFreeFraction[d];
                    values[column++] = (float) clusterFreeFractionStd[d];
                }
                if (column != NAMES.size()) {
                    throw new IllegalStateException("Feature count does not match names");
                }
                for (int i = 0; i < values.length; i++) {
                    if (!Float.isFinite(values[i])) {
                        throw new IllegalArgumentException("Non-finite feature " + NAMES.get(i));
                    }
                }
            }
            return result;
        }
    }

    private static double ratio(double value, double divisor) {
        return divisor > 0 ? value / divisor : 0;
    }

    static float logKnownIops(double value) {
        if (value == Double.MAX_VALUE || !Double.isFinite(value)) {
            return Float.NaN;
        }
        return (float) Math.log1p(Math.max(0, value));
    }
}
