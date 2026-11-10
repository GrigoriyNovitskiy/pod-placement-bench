package one.cloud.benchmark.model;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuTopologyTest {
    @Test
    void synthesizesDistinctHostAndTypeRangesAndQuantizesRequestShares() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11, 17}, 2, 1), alloc(2, new int[]{11, 17}, .6, .25),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2));
        assertEquals(6, model.gpus.size());
        for (int host = 1; host <= 2; host++) {
            for (final int dimension : new int[]{11, 17}) {
                final int first = model.gpus.first(host, dimension);
                final int end = model.gpus.end(host, dimension);
                assertEquals(dimension == 11 ? 2 : 1, end - first);
                for (int gpu = first; gpu < end; gpu++) {
                    assertEquals(host, model.gpus.host(gpu));
                    assertEquals(dimension, model.gpus.type(gpu));
                }
            }
        }
        assertEquals(12, model.gpus.demand(3, 11));
        assertEquals(5, model.gpus.demand(3, 17));
        assertThrows(IllegalArgumentException.class, () -> model.gpus.host(0));
        assertThrows(IllegalArgumentException.class, () -> model.gpus.type(7));
        assertThrows(IllegalArgumentException.class, () -> model.gpus.first(0, 11));
        assertThrows(IllegalArgumentException.class, () -> model.gpus.first(1, 1));
    }

    @Test
    void deviceModeRejectsAmbiguousInputsWhileAggregateModeKeepsAcceptingThem() throws Exception {
        for (final double[] values : new double[][]{{1.5, .6}, {Double.MIN_VALUE, .6}, {2, 1.5}, {2, .06}, {2, .001}, {2, .30000001}}) {
            assertNull(gpuModel(ClusterSpec.GpuMode.AGGREGATE, values[0], values[1]).gpus);
            assertThrows(IllegalArgumentException.class,
                    () -> gpuModel(ClusterSpec.GpuMode.DEVICES_20, values[0], values[1]));
        }
        assertEquals(12, gpuModel(ClusterSpec.GpuMode.DEVICES_20, 2, .6).gpus.demand(2, 11));
        assertEquals(12, gpuModel(ClusterSpec.GpuMode.DEVICES_20, 2, Math.nextUp(.6)).gpus.demand(2, 11));
        assertEquals(6, gpuModel(ClusterSpec.GpuMode.DEVICES_20, 2, (double) .3f).gpus.demand(2, 11));
        assertEquals(40, gpuModel(ClusterSpec.GpuMode.DEVICES_20, 2, 2).gpus.demand(2, 11));
    }

    private static ClusterSpec gpuModel(ClusterSpec.GpuMode mode, double supply, double demand) throws Exception {
        return model(mode, alloc(1, new int[]{11}, supply), alloc(2, new int[]{11}, demand),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2));
    }
}
