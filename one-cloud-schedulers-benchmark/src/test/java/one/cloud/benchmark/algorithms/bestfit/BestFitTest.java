package one.cloud.benchmark.algorithms.bestfit;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.DEVICE_EXTENTS_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.MEM_MIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.SSD_GIB_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType.VCORES_VALUE;
import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BestFitTest {
    @Test
    void choosesTheTightestFeasibleHost() throws Exception {
        final var engine = new Engine(model(
                cpu(1, 10), cpu(2, 6), cpu(3, 3), cpu(4, 4), host(1), pod(2),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(2)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 1),
                resource(4, 4, 2), resource(5, 2, 3)));

        try (final var algorithm = new BestFit(engine, 42)) {
            engine.schedule(algorithm, new Request(4));
        }

        assertEquals(2, engine.placement(4).host());
        assertEquals(5, engine.placement(4).numa());
        assertEquals(2, engine.available(2, VCORES_VALUE));
        assertEquals(10, engine.available(1, VCORES_VALUE));
        assertEquals(3, engine.available(3, VCORES_VALUE));
    }

    @Test
    void sumsNormalizedCpuAndMemoryAfterEarlierPlacements() throws Exception {
        final var spec = model(
                cpuAndMemory(1, 8, 8), cpuAndMemory(2, 100, 100),
                cpuAndMemory(3, 24, 85), cpuAndMemory(4, 4, 1),
                host(1), rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setPinnedHost(2)), pod(3),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 3));
        final var empty = new Engine(spec);
        final var occupied = new Engine(spec);

        try (final var algorithm = new BestFit(empty, 42)) {
            empty.schedule(algorithm, new Request(4));
        }
        try (final var algorithm = new BestFit(occupied, 42)) {
            occupied.schedule(algorithm, new Request(3), new Request(4));
        }

        assertEquals(1, empty.placement(4).host());
        assertEquals(2, occupied.placement(3).host());
        assertEquals(2, occupied.placement(4).host());
        assertEquals(72, occupied.available(2, VCORES_VALUE));
        assertEquals(14, occupied.available(2, MEM_MIB_VALUE));
    }

    @Test
    void scoresActualDiskBudgetsAndSkipsInvalidOneStripeFallback() throws Exception {
        final var engine = new Engine(model(
                alloc(1, new int[]{SSD_GIB_VALUE}, 1), alloc(2, new int[]{SSD_GIB_VALUE}, 100),
                alloc(3, new int[]{DEVICE_EXTENTS_VALUE}, 20), alloc(4, new int[]{DEVICE_EXTENTS_VALUE}, 10),
                alloc(5, new int[0]), host(1),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1)
                        .setMedia(Media.HDD).setAlternativeMedia(Media.SSD)),
                ssd(3, 2), ssd(4, 3),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD)
                        .addDisks(DiskPlan.newBuilder().setMedia(Media.SSD).setLayout(Layout.STRIPED)
                                .setExtents(5).setMetadataExtents(1).setMinDevices(2).setMaxDevices(2)
                                .setMinStripes(2).setMaxStripes(2))),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 1, 1),
                resource(4, 4, 2), resource(5, 3, 3), resource(6, 3, 3),
                resource(7, 4, 4), resource(8, 4, 4), resource(9, 5, 5)));

        try (final var algorithm = new BestFit(engine, 42)) {
            engine.schedule(algorithm, new Request(9));
        }

        final var assignment = engine.placement(9);
        assertEquals(3, assignment.host());
        assertArrayEquals(new int[]{7, 8}, assignment.devices());
        assertEquals(12.0 / 1024, engine.available(3, SSD_GIB_VALUE));
        assertEquals(40.0 / 1024, engine.available(2, SSD_GIB_VALUE));
    }

    @Test
    void placesRequestsAtomicallyAndDeclinesWhenNoPlanFits() throws Exception {
        final var engine = new Engine(model(
                cpu(1, 5), cpu(2, 7), cpu(3, 3), host(1), pod(2),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2),
                resource(4, 3, 2), resource(5, 3, 2), resource(6, 3, 2)));

        try (final var algorithm = new BestFit(engine, 42)) {
            engine.schedule(algorithm, new Request(3, 4), new Request(5, 6));
        }

        assertEquals(2, engine.placement(3).host());
        assertEquals(2, engine.placement(4).host());
        assertNull(engine.placement(5));
        assertNull(engine.placement(6));
        assertEquals(1, engine.revision());
        assertEquals(5, engine.available(1, VCORES_VALUE));
        assertEquals(1, engine.available(2, VCORES_VALUE));
    }

    @Test
    void breaksTiesUsingTheSeed() throws Exception {
        final var spec = model(
                cpu(1, 8), cpu(2, 3), host(1), pod(2),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2));
        for (long seed = 0; seed < 8; seed++) {
            final var engine = new Engine(spec);
            try (final var algorithm = new BestFit(engine, seed)) {
                engine.schedule(algorithm, new Request(3));
            }

            final int expected = new SplittableRandom(seed).nextInt(2) == 0 ? 2 : 1;
            assertEquals(expected, engine.placement(3).host());
        }
    }

    private static Record cpu(int id, double cores) {
        return alloc(id, new int[]{VCORES_VALUE}, cores);
    }

    private static Record cpuAndMemory(int id, double cores, double memory) {
        return alloc(id, new int[]{VCORES_VALUE, MEM_MIB_VALUE}, cores, memory);
    }

    private static Record host(int id) {
        return rules(Constraints.newBuilder().setId(id).setKind(Kind.HOST));
    }

    private static Record pod(int id) {
        return rules(Constraints.newBuilder().setId(id).setKind(Kind.POD));
    }

    private static Record ssd(int id, int host) {
        return rules(Constraints.newBuilder().setId(id).setKind(Kind.DISK).setParent(host).setMedia(Media.SSD));
    }
}
