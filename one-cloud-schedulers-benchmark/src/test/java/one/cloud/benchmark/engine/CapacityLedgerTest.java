package one.cloud.benchmark.engine;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapacityLedgerTest {
    @Test
    void gpuPhysicalCountingUsesDevicesAndTentativeAssignmentsWithoutMutatingState() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .5), alloc(3, new int[]{11}, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2)));
        final int gpu = state.model().gpus.first(1, 11);
        final var planner = new GreedyPlanner(state);
        final var sample = new Request(3);
        assertEquals(2, planner.fitCount(sample, 1, 100));
        assertEquals(1, planner.fitCount(sample, 1, 1));
        final var tentative = new Assignment[]{new Assignment(2, 1, 0, new int[]{0}, new int[0], new int[]{gpu})};
        assertEquals(1, planner.fitCount(sample, 1, tentative, 100));
        assertEquals(2, planner.fitCount(sample, 1, 100));
        assertEquals(20, state.gpuAvailable(gpu));
        assertEquals(20, state.gpuAvailable(gpu + 1));
        assertEquals(2, state.available(1, 11));
        assertEquals(0, state.revision());
    }

    @Test
    void gpuBestFitUsesTheSmallestAdequateCardAndPreservesWholeDevices() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .4), alloc(3, new int[]{11}, .5),
                alloc(4, new int[]{11}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 4, 2)));
        final int gpu = state.model().gpus.first(1, 11);
        final var planner = new GreedyPlanner(state);
        final var algorithm = new SchedulingAlgorithm(state, 1) {
            @Override
            public void schedule(Request request) {
                final var plans = planner.plan(request, 1, 0);
                assertNotNull(plans);
                context.place(request, plans);
            }
        };
        state.schedule(algorithm, new Request(2), new Request(3), new Request(4));
        assertArrayEquals(new int[]{gpu}, state.placement(2).gpus());
        assertArrayEquals(new int[]{gpu}, state.placement(3).gpus());
        assertArrayEquals(new int[]{gpu + 1}, state.placement(4).gpus());
        assertEquals(2, state.gpuAvailable(gpu));
        assertEquals(0, state.gpuAvailable(gpu + 1));
    }

    @Test
    void gpuScratchAllocationRollsBackWhenNumaDiskGeometryMustBeRetried() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 2, 11}, 2, 4, 1), alloc(2, new int[]{1, 2}, 1, 2),
                alloc(3, new int[]{18}, 10), alloc(4, new int[]{1, 11}, 1, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.DISK).setParent(1)
                        .setMedia(Media.MEMDISK).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)
                        .addDisks(disk(Media.MEMDISK).setExtents(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 4, 5)));
        final int gpu = state.model().gpus.first(1, 11);
        final var planner = new GreedyPlanner(state);
        final var request = new Request(5);
        assertEquals(1, planner.fitCount(request, 1, 10));
        final var algorithm = new SchedulingAlgorithm(state, 1) {
            @Override
            public void schedule(Request request) {
                assertNull(planner.plan(request, 1, 2));
                final var plans = planner.plan(request, 1, 3);
                assertNotNull(plans);
                assertArrayEquals(new int[]{gpu}, plans[0].gpus());
                context.place(request, plans);
            }
        };
        state.schedule(algorithm, request);
        assertEquals(8, state.gpuAvailable(gpu));
        assertEquals(9, state.available(4, 18));
        state.remove(request);
        assertEquals(20, state.gpuAvailable(gpu));
        assertEquals(10, state.available(4, 18));
        assertEquals(1, planner.fitCount(request, 1, 10));
    }

    @Test
    void numaFragmentationAndDedicatedCpuUseBothLedgers() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2, 15}, 24, 8, 8),
                alloc(2, new int[]{1, 2, 15}, 12, 4, 4),
                alloc(3, new int[]{2}, 6), alloc(4, new int[]{1, 15}, 1, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(3)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 4, 4));
        final var capacity = new CapacityLedger(model);
        assertNull(capacity.prepare(plain(4, 0)));
        assertNull(capacity.prepare(plain(4, 2)));
        assertNull(capacity.prepare(plain(4, 3)));
        final var debit = capacity.prepare(plain(5, 2));
        assertNotNull(debit);
        capacity.allocate(debit);
        assertEquals(18, capacity.available(1, 1));
        assertEquals(6, capacity.available(2, 1));
        assertEquals(6, capacity.available(1, 15));
        capacity.release(debit);
        assertEquals(24, capacity.available(1, 1));
        assertEquals(12, capacity.available(2, 1));
        final var planner = new GreedyPlanner(new Engine(model));
        assertEquals(0, planner.fitCount(new Request(4), 1, 100));
        assertEquals(4, planner.fitCount(new Request(5), 1, 100));
    }

    @Test
    void layoutsChargeRoundedPhysicalGeometryInsteadOfTheSummary() throws Exception {
        final Layout[] layouts = {Layout.AUTO, Layout.LINEAR, Layout.STRIPED, Layout.RAID1,
                Layout.RAID4, Layout.RAID5, Layout.RAID6, Layout.RAID10};
        final int[] counts = {2, 1, 2, 2, 3, 3, 5, 4};
        final int[] perDevice = {5, 9, 5, 10, 6, 6, 4, 6};
        for (int index = 0; index < layouts.length; index++) {
            final int count = counts[index];
            final boolean reserved = index >= 3;
            final var disk = disk(Media.HDD).setLayout(layouts[index]).setExtents(9)
                    .setMinDevices(count).setMaxDevices(count).setMaxStripes(16)
                    .setMetadataExtents(reserved ? 1 : 0);
            final var model = disks(count, disk);
            final var capacity = new CapacityLedger(model);
            final int[] devices = new int[count];
            for (int device = 0; device < count; device++) {
                devices[device] = device + 2;
            }
            final var debit = capacity.prepare(new Assignment(count + 2, 1, 0, new int[]{0, count}, devices));
            assertNotNull(debit, layouts[index].name());
            capacity.allocate(debit);
            for (final int device : devices) {
                assertEquals(20 - perDevice[index], capacity.available(device, 18), layouts[index].name());
            }
            assertEquals(count * (20 - perDevice[index]) / 1024.0, capacity.available(1, 9), 1e-12);
            capacity.release(debit);
            assertEquals(count * 20 / 1024.0, capacity.available(1, 9));
        }
    }

    @Test
    void iopsCapsAreAppliedAfterSplittingAndUnknownDemandRemainsUncharged() throws Exception {
        final var model = disks(2, disk(Media.HDD).setExtents(2).setIops(100).setPerDeviceIopsCap(3)
                .setMinDevices(2).setMaxDevices(2).setMaxStripes(2));
        final var capacity = new CapacityLedger(model);
        final var plan = new Assignment(4, 1, 0, new int[]{0, 2}, new int[]{2, 3});
        final var debit = capacity.prepare(plan);
        assertNotNull(debit);
        capacity.allocate(debit);
        assertEquals(7, capacity.available(2, 20));
        assertEquals(7, capacity.available(3, 20));
        final var unknown = new CapacityLedger(disks(1, disk(Media.HDD).clearIops().setPerDeviceIopsCap(3)));
        final var unknownDebit = unknown.prepare(new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2}));
        assertNotNull(unknownDebit);
        unknown.allocate(unknownDebit);
        assertEquals(10, unknown.available(2, 20));
    }

    @Test
    void wholeDiskOwnershipIsExclusiveAndReversible() throws Exception {
        final var capacity = new CapacityLedger(disks(1, disk(Media.HDD).setFlags(1)));
        final var plan = new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2});
        final var debit = capacity.prepare(plan);
        assertNotNull(debit);
        capacity.allocate(debit);
        assertEquals(0, capacity.available(2, 18));
        assertNull(capacity.prepare(plan));
        capacity.release(debit);
        assertEquals(20, capacity.available(2, 18));
        assertNotNull(capacity.prepare(plan));
        assertNull(capacity.prepare(plan, plan));
        assertEquals(20, capacity.available(2, 18));
    }

    @Test
    void alternativeMediaShareExtentsAndExcludeOnlyNonzeroSpindles() throws Exception {
        final var model = model(
                alloc(1, new int[]{7, 8, 9, 10}, 100, 100, 100, 100),
                alloc(2, new int[]{18, 19, 20}, 20, 1, 10), alloc(3, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1)
                        .setMedia(Media.HDD).setAlternativeMedia(Media.SSD).setAlternativeSpindles(4)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)
                        .addDisks(disk(Media.SSD).setSpindles(3).setMinDevices(3).setMaxDevices(3))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk(Media.HDD).setSpindles(1))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).addDisks(disk(Media.HDD))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 3, 4), resource(5, 3, 5));
        final var capacity = new CapacityLedger(model);
        final var alternative = capacity.prepare(new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2}));
        assertNotNull(alternative);
        capacity.allocate(alternative);
        assertEquals(19, capacity.available(2, 18));
        assertEquals(0, capacity.available(2, 19));
        assertEquals(1, capacity.available(2, 19, Media.SSD_VALUE));
        assertNull(capacity.prepare(new Assignment(4, 1, 0, new int[]{0, 1}, new int[]{2})));
        final var zeroSpindles = capacity.prepare(new Assignment(5, 1, 0, new int[]{0, 1}, new int[]{2}));
        assertNotNull(zeroSpindles);
        capacity.allocate(zeroSpindles);
        capacity.release(alternative);
        assertEquals(1, capacity.available(2, 19));
        assertEquals(4, capacity.available(2, 19, Media.SSD_VALUE));
        assertEquals(19, capacity.available(2, 18));
        capacity.release(zeroSpindles);
        assertEquals(20, capacity.available(2, 18));
        final var planner = new GreedyPlanner(new Engine(model));
        assertEquals(1, planner.fitCount(new Request(3), 1, 100));
    }

    @Test
    void combinedPlansCannotOversubscribeAndMalformedPlansAlwaysThrow() throws Exception {
        final var model = disks(1, disk(Media.HDD).setExtents(11));
        final var capacity = new CapacityLedger(model);
        final var plan = new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2});
        assertNull(capacity.prepare(plan, plan));
        assertEquals(20, capacity.available(2, 18));
        final var broken = new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2});
        broken.devices()[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> capacity.prepare(plan, broken));
        assertThrows(IllegalArgumentException.class,
                () -> capacity.prepare(new Assignment(3, 1, 0, new int[]{0, 1, 1}, new int[]{2})));
        assertEquals(20, capacity.available(2, 18));
        final var planner = new GreedyPlanner(new Engine(model));
        final var request = new Request(3);
        assertThrows(IllegalArgumentException.class, () -> planner.numaNodes(0));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(request, 2, 0));
        for (final int numa : new int[]{-1, 2}) {
            assertThrows(IllegalArgumentException.class, () -> planner.plan(request, 1, numa));
        }
    }

    @Test
    void internalNumaColocationSurvivesPhysicalCounting() throws Exception {
        final var model = model(
                alloc(1, new int[]{2}, 8), alloc(2, new int[]{2}, 4), alloc(3, new int[]{2}, 3),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)
                        .addLinks(Link.newBuilder().setTarget(5).setDomain(2).setRelation(Relation.COLOCATE))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 3, 5));
        final var planner = new GreedyPlanner(new Engine(model));
        assertEquals(0, planner.fitCount(new Request(4, 5), 1, 100));
        assertEquals(2, planner.fitCount(new Request(4), 1, 100));
    }

    @Test
    void memdiskConsumesTheSelectedNumaMemoryAndOptionalVolumesMayBeSkipped() throws Exception {
        final var model = model(
                alloc(1, new int[]{2}, 8), alloc(2, new int[]{2}, 4),
                alloc(3, new int[]{18}, 4), alloc(4, new int[]{2}, 3),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.DISK).setParent(1).setMedia(Media.MEMDISK).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).addDisks(disk(Media.MEMDISK).setExtents(3))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 4, 5));
        final var capacity = new CapacityLedger(model);
        assertNull(capacity.prepare(new Assignment(5, 1, 3, new int[]{0, 1}, new int[]{4})));
        final var debit = capacity.prepare(new Assignment(5, 1, 2, new int[]{0, 1}, new int[]{4}));
        assertNotNull(debit);
        capacity.allocate(debit);
        assertEquals(5, capacity.available(1, 2));
        assertEquals(1, capacity.available(2, 2));
        assertEquals(1, capacity.available(4, 18));
        capacity.release(debit);
        assertEquals(8, capacity.available(1, 2));
        final var planner = new GreedyPlanner(new Engine(model));
        assertEquals(1, planner.fitCount(new Request(5), 1, 100));
        final var optional = new GreedyPlanner(new Engine(disks(1, disk(Media.HDD).setExtents(100).setFlags(2))));
        assertEquals(7, optional.fitCount(new Request(3), 1, 7));
    }

    @Test
    void hostStorageProjectsBothMediaAndRestoresSpindlesOnlyAfterTheLastOwner() throws Exception {
        final var model = model(
                alloc(1, new int[]{9, 10}, 20 / 1024.0, 2),
                alloc(2, new int[]{18, 19, 20}, 20, 2, 10), alloc(3, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1)
                        .setMedia(Media.HDD).setAlternativeMedia(Media.SSD).setAlternativeSpindles(8)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD).addDisks(disk(Media.HDD).setSpindles(1))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk(Media.SSD).setSpindles(2)
                        .setMinDevices(2).setMaxDevices(4).setMaxStripes(4))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 3, 4));
        final var capacity = new CapacityLedger(model);
        assertEquals(20 / 1024.0, capacity.available(1, 7));
        assertEquals(8, capacity.available(1, 8));
        final var alternative = capacity.prepare(new Assignment(4, 1, 0, new int[]{0, 1}, new int[]{2}));
        assertNotNull(alternative);
        capacity.allocate(alternative);
        assertEquals(19 / 1024.0, capacity.available(1, 7));
        assertEquals(19 / 1024.0, capacity.available(1, 9));
        assertEquals(0, capacity.available(1, 10));
        assertEquals(6, capacity.available(1, 8));
        capacity.release(alternative);
        final var plan = new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2});
        final var first = capacity.prepare(plan);
        capacity.allocate(first);
        final var second = capacity.prepare(plan);
        capacity.allocate(second);
        assertEquals(18 / 1024.0, capacity.available(1, 7));
        assertEquals(0, capacity.available(1, 8));
        capacity.release(first);
        assertEquals(19 / 1024.0, capacity.available(1, 9));
        assertEquals(1, capacity.available(1, 10));
        assertEquals(0, capacity.available(1, 8));
        capacity.release(second);
        assertEquals(20 / 1024.0, capacity.available(1, 7));
        assertEquals(8, capacity.available(1, 8));
        assertEquals(2, capacity.available(1, 10));
    }

    @Test
    void scalarFastPathMatchesRepeatedAllocationAtFractionalBoundaries() throws Exception {
        for (final double amount : new double[]{0.1, 0.2, 0.3, 0.5, 1, 2}) {
            for (final double budget : new double[]{0.3, 1, 3, 10, 100}) {
                for (final boolean numa : new boolean[]{false, true}) {
                    final var records = new ArrayList<Record>(List.of(
                            alloc(1, new int[]{1}, numa ? budget * 2 : budget),
                            alloc(2, new int[]{1}, budget), alloc(3, new int[]{1}, amount),
                            rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                            rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)), resource(1, 1, 1)));
                    if (numa) {
                        records.add(rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(1)));
                        records.add(rules(Constraints.newBuilder().setId(4).setKind(Kind.NUMA).setParent(1).setOrdinal(2)));
                        records.add(resource(2, 2, 3));
                        records.add(resource(3, 2, 4));
                    }
                    final int resource = numa ? 4 : 2;
                    records.add(resource(resource, 3, 2));
                    final var model = model(records.toArray(Record[]::new));
                    final var planner = new GreedyPlanner(new Engine(model));
                    final var request = new Request(resource);
                    for (final int cap : new int[]{1, 10, 100, 1000}) {
                        assertEquals(slowCount(model, resource, numa, cap, null), planner.fitCount(request, 1, cap),
                                amount + "/" + budget + "/" + numa + "/" + cap);
                        final var candidate = plain(resource, numa ? 2 : 0);
                        if (new CapacityLedger(model).prepare(candidate) != null) {
                            assertEquals(slowCount(model, resource, numa, cap, candidate),
                                    planner.fitCount(request, 1, new Assignment[]{candidate}, cap));
                        }
                    }
                }
            }
        }
    }

    @Test
    void geometricScoreAveragesActualScalarFamiliesIncludingNumaAndDedicatedCpu() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 2, 3, 4, 11, 12, 13, 14, 15, 16, 17},
                        24, 16, 10, 5, 4, 24, 20, 10, 8, 4, 2),
                alloc(2, new int[]{1, 2, 12, 15, 16}, 12, 8, 12, 4, 2),
                alloc(3, new int[]{1, 2, 3, 4, 11, 12, 13, 14, 15, 16, 17},
                        1, 3, 2, 1, 1, 3, 10, 2, 2, 1, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST).setCpuPower(3)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4));
        final var state = new Engine(model);
        final var scorer = new GreedyPlanner(state).normalizedResidualL2Scorer();
        assertEquals(.4921875 + .525390625 + .445 + .64 + .40625 + .5625 + .25,
                scorer.applyAsDouble(new Assignment[]{plain(4, 2)}), 1e-12);
        assertEquals(0, state.revision());
        assertEquals(24, state.available(1, 1));
        assertEquals(12, state.available(2, 1));
    }

    @Test
    void geometricScoreAggregatesPhysicalCellsAndKeepsGenuineScalarStorage() throws Exception {
        final var shared = model(
                alloc(1, new int[]{9}, 100), alloc(2, new int[]{18}, 10), alloc(3, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)
                        .addDisks(disk(Media.HDD).setExtents(2)).addDisks(disk(Media.HDD).setExtents(3))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3));
        assertEquals(.25, score(shared, new Assignment(3, 1, 0, new int[]{0, 1, 2}, new int[]{2, 2})), 1e-12);
        final var scalar = model(
                alloc(1, new int[]{9}, 100), alloc(2, new int[]{18}, 20), alloc(3, new int[]{9}, 4 / 1024.0),
                alloc(4, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk(Media.HDD).setExtents(5))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 4, 4));
        assertEquals(.60125, score(scalar, plain(3, 0),
                new Assignment(4, 1, 0, new int[]{0, 1}, new int[]{2})), 1e-12);
        final var memdisk = model(
                alloc(1, new int[]{2}, 8), alloc(2, new int[]{2}, 4), alloc(3, new int[]{18}, 4),
                alloc(4, new int[]{2}, 99),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.DISK).setParent(1).setMedia(Media.MEMDISK).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).addDisks(disk(Media.MEMDISK).setExtents(3))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3), resource(4, 3, 4), resource(5, 4, 5));
        assertEquals(.171875, score(memdisk, new Assignment(5, 1, 2, new int[]{0, 1}, new int[]{4})), 1e-12);
    }

    @Test
    void geometricScoreUsesPrimarySpaceAndSelectedMediaSpindlesWithoutExclusionLoss() throws Exception {
        final var model = model(
                alloc(1, new int[]{9, 10}, 40 / 1024.0, 5),
                alloc(2, new int[]{18, 19}, 20, 1), alloc(3, new int[]{18, 19}, 20, 4), alloc(4, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)
                        .setAlternativeMedia(Media.SSD).setAlternativeSpindles(8)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).addDisks(disk(Media.SSD).setSpindles(2))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).addDisks(disk(Media.HDD).setExtents(10))),
                rules(Constraints.newBuilder().setId(6).setKind(Kind.SHARD).addDisks(disk(Media.HDD).setExtents(10).setSpindles(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3),
                resource(4, 4, 4), resource(5, 4, 5), resource(6, 4, 6));
        final var alternative = new Assignment(4, 1, 0, new int[]{0, 1}, new int[]{2});
        assertEquals(1.465, score(model, alternative), 1e-12);
        assertEquals(1.13875, score(model, alternative,
                new Assignment(5, 1, 0, new int[]{0, 1}, new int[]{3})), 1e-12);
        assertEquals(1.70125, score(model, alternative,
                new Assignment(6, 1, 0, new int[]{0, 1}, new int[]{3})), 1e-12);
    }

    @Test
    void geometricScoreUsesRaidAndWholeGeometryAndOmitsUnboundedIopsCells() throws Exception {
        final var raid = disks(2, disk(Media.HDD).setLayout(Layout.RAID1).setExtents(9).setMetadataExtents(1)
                .setMinDevices(2).setMaxDevices(2));
        assertEquals(.25, score(raid, new Assignment(4, 1, 0, new int[]{0, 2}, new int[]{2, 3})), 1e-12);
        assertEquals(0, score(disks(1, disk(Media.HDD).setFlags(1)),
                new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2})));
        final var striped = model(
                alloc(1, new int[]{9}, 100), alloc(2, new int[]{18, 20}, 10, Double.MAX_VALUE),
                alloc(3, new int[]{18, 20}, 10, 100), alloc(4, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD)
                        .addDisks(disk(Media.HDD).setLayout(Layout.STRIPED).setExtents(2).setIops(40)
                                .setMinDevices(2).setMaxDevices(2).setMaxStripes(2))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2), resource(4, 4, 3));
        assertEquals(1.45, score(striped, new Assignment(4, 1, 0, new int[]{0, 2}, new int[]{2, 3})), 1e-12);
        assertEquals(.9025, score(disks(1, disk(Media.HDD)),
                new Assignment(3, 1, 0, new int[]{0, 1}, new int[]{2})), 1e-12);
    }

    @Test
    void geometricScoreUsesTheAssignedGpuInsteadOfTheHostAggregate() throws Exception {
        final var model = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2));
        final int gpu = model.gpus.first(1, 11);
        assertEquals(.64, score(model, new Assignment(2, 1, 0, new int[]{0}, new int[0], new int[]{gpu})), 1e-12);
    }

    @Test
    void geometricScoreRetainsInitialCapacityReloadsSupplyAndDoesNotMutateState() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 10), alloc(2, new int[]{1}, 6), alloc(3, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2));
        final var state = new Engine(model);
        final var prefill = new Request(2);
        final var request = new Request(3);
        final var planner = new GreedyPlanner(state);
        try (final var algorithm = new SchedulingAlgorithm(state, 42) {
            @Override
            public void schedule(Request pending) {
                if (pending == prefill) {
                    context.place(pending, plain(2, 0));
                    return;
                }
                final var scorer = planner.normalizedResidualL2Scorer();
                final var plans = planner.plan(pending, 1, 0);
                assertNotNull(plans);
                assertEquals(.04, scorer.applyAsDouble(plans), 1e-12);
                assertEquals(.04, scorer.applyAsDouble(plans), 1e-12);
                assertEquals(4, context.available(1, 1));
                assertEquals(1, context.revision());
                context.place(pending, plans);
            }
        }) {
            state.schedule(algorithm, prefill, request);
            final var scorer = planner.normalizedResidualL2Scorer();
            state.remove(request);
            state.remove(prefill);
            assertEquals(.64, scorer.applyAsDouble(new Assignment[]{plain(3, 0)}), 1e-12);
            assertEquals(10, state.available(1, 1));
            assertEquals(4, state.revision());
        }
    }

    @Test
    void geometricScoreHandlesZeroAndSubnormalBudgetsAndRejectsMalformedPlans() throws Exception {
        for (final double full : new double[]{0, Double.MIN_VALUE, 2 * Double.MIN_VALUE}) {
            final var model = model(
                    alloc(1, new int[]{1}, full), alloc(2, new int[]{1}, Double.MIN_VALUE), alloc(3, new int[0]),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                    resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 2));
            final var scorer = new GreedyPlanner(new Engine(model)).normalizedResidualL2Scorer();
            assertEquals(0, scorer.applyAsDouble(new Assignment[]{plain(3, 0)}));
            if (full == 0) {
                assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[]{plain(2, 0)}));
            } else {
                assertEquals(full == Double.MIN_VALUE ? 0 : .25,
                        scorer.applyAsDouble(new Assignment[]{plain(2, 0)}));
            }
        }
        final var model = model(
                alloc(1, new int[]{1}, 10), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2));
        final var scorer = new GreedyPlanner(new Engine(model)).normalizedResidualL2Scorer();
        final var first = new Assignment(3, 1, 0, new int[]{0}, new int[0]);
        assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[0]));
        assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[]{first, first}));
        assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[]{first,
                new Assignment(4, 2, 0, new int[]{0}, new int[0])}));
        assertThrows(IllegalArgumentException.class, () -> scorer.applyAsDouble(new Assignment[]{
                new Assignment(3, 1, 0, new int[]{0, 0}, new int[0])}));
        assertEquals(.64, scorer.applyAsDouble(new Assignment[]{first}), 1e-12);
    }

    private static double score(ClusterSpec model, Assignment... plans) {
        return new GreedyPlanner(new Engine(model)).normalizedResidualL2Scorer().applyAsDouble(plans);
    }

    private static int slowCount(ClusterSpec model, int resource, boolean numa, int cap, Assignment tentative) {
        final var capacity = new CapacityLedger(model);
        if (tentative != null) {
            capacity.allocate(capacity.prepare(tentative));
        }
        int count = 0;
        final int[] nodes = numa ? new int[]{2, 3} : new int[]{0};
        while (count < cap) {
            CapacityLedger.Debit debit = null;
            for (final int node : nodes) {
                debit = capacity.prepare(plain(resource, node));
                if (debit != null) {
                    break;
                }
            }
            if (debit == null) {
                break;
            }
            capacity.allocate(debit);
            count++;
        }
        return count;
    }

    private static Assignment plain(int resource, int numa) {
        return new Assignment(resource, 1, numa, new int[]{0}, new int[0]);
    }

    private static DiskPlan.Builder disk(Media media) {
        return DiskPlan.newBuilder().setMedia(media).setExtents(1).setMinDevices(1).setMaxDevices(1)
                .setMinStripes(1).setMaxStripes(1);
    }

    private static ClusterSpec disks(int count, DiskPlan.Builder disk) throws Exception {
        final var records = new ArrayList<Record>(List.of(
                alloc(1, new int[]{5, 6, 7, 8, 9, 10}, 100, 100, 100, 100, 100, 100),
                alloc(2, new int[]{18, 19, 20}, 20, 10, 10), alloc(3, new int[]{9, 10}, 99, 99),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(disk.getMedia())),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.SHARD).addDisks(disk)), resource(1, 1, 1)));
        for (int index = 0; index < count; index++) {
            records.add(resource(index + 2, 2, 2));
        }
        records.add(resource(count + 2, 3, 3));
        return model(records.toArray(Record[]::new));
    }
}
