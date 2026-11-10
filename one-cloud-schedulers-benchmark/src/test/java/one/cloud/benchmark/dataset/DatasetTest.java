package one.cloud.benchmark.dataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Candidates;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DeviceUse;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Domain;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Operation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Placement;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetTest {
    @TempDir
    Path temporary;

    @Test
    void decodesFlatProfilesSnapshotCopiesAndTrace() throws Exception {
        final var model = temporary.resolve("snapshot.pb");
        write(model, header(), records());
        final var trace = temporary.resolve("operations.pb");
        write(trace, Header.newBuilder().setVersion(2).setDatasetId(42).setResourceCount(205)
                .setStream(PlacementDatasetProtos.Stream.OPERATIONS).setOperationCount(2).setSyntheticOperations(true).build(), List.of(
                Record.newBuilder().setOperation(Operation.newBuilder().setTime(1).setResource(203).setGeneration(1).setCreate(true)).build(),
                Record.newBuilder().setOperation(Operation.newBuilder().setTime(2).setResource(203).setGeneration(1)).build()));
        final var dataset = Dataset.load(model, trace);
        assertEquals(206, dataset.alloc.length);
        assertEquals(4, dataset.alloc[203]);
        assertEquals(4, dataset.constraints[203]);
        assertEquals(2, dataset.allocations.amount[dataset.allocations.offset[6]]);
        assertEquals(2, dataset.rules.linkOffset[5] - dataset.rules.linkOffset[4]);
        assertEquals(PlacementDatasetProtos.Relation.SHARED_GROUP_VALUE, dataset.rules.linkKind[1]);
        assertEquals(201, dataset.snapshot.numa[0]);
        assertArrayEquals(new int[]{203, 204, 205}, dataset.snapshot.resource);
        assertEquals(0.5, dataset.snapshot.spindles[0]);
        assertTrue(Double.isNaN(dataset.snapshot.iops[1]));
        assertEquals(Media.SSD_VALUE, dataset.rules.alternativeMedia[3]);
        assertEquals(0.5, dataset.rules.alternativeSpindles[3]);
        assertEquals(Integer.MAX_VALUE, dataset.rules.maxDevices[0]);
        assertTrue(Double.isNaN(dataset.rules.diskIops[0]));
        assertEquals(72, dataset.rules.diskIopsCap[0]);
        assertEquals(1, dataset.rules.metadataExtents[0]);
        assertEquals(PlacementDatasetProtos.Layout.RAID4_VALUE, dataset.rules.diskLayout[0]);
        assertTrue(dataset.hasSnapshot);
        assertTrue(dataset.operations.synthetic);
        assertFalse(dataset.operations.replayFromSnapshot);
        assertArrayEquals(new int[]{203, 203}, dataset.operations.resource);
        assertTrue(dataset.operations.create[0]);
        assertFalse(dataset.operations.create[1]);
        assertTrue(dataset.candidates.allows(0, 200));
        assertFalse(dataset.candidates.allows(1, 1));
        assertTrue(dataset.candidates.allows(2, 1));
        assertFalse(dataset.candidates.allows(2, 2));
        assertTrue(dataset.candidates.allows(3, 200));
        assertFalse(dataset.candidates.allows(3, 201));
    }

    @Test
    void rejectsInvalidCountsReferencesAndFraming() throws Exception {
        final var model = temporary.resolve("broken.pb");
        write(model, header().toBuilder().setAllocValueCount(11).build(), records());
        assertThrows(IOException.class, () -> Dataset.load(model));
        final var rows = records();
        rows.set(rows.size() - 1, Record.newBuilder().setPlacement(Placement.newBuilder()
                .setResource(205).setHost(2).setNuma(201)).build());
        write(model, header().toBuilder().setDeviceUseCount(1).build(), rows);
        assertThrows(IOException.class, () -> Dataset.load(model));
        for (final var malformed : List.of(new byte[]{0}, new byte[]{1}, new byte[]{(byte) 128},
                new byte[]{(byte) 128, (byte) 128, (byte) 128, 32}, new byte[]{2, 0x78, 1})) {
            Files.write(model, malformed);
            assertThrows(IOException.class, () -> Dataset.load(model));
        }
    }

    @Test
    void readsStreamsLargerThanTheFrameLimit() throws Exception {
        final var candidates = Candidates.newBuilder().setId(2);
        for (int host = 1; host <= 10000; host++) {
            candidates.addHost(host);
        }
        final var record = Record.newBuilder().setCandidates(candidates).build();
        final int count = 32 * 1024 * 1024 / record.getSerializedSize() + 2;
        final var path = temporary.resolve("large.pb");
        try (final var output = Files.newOutputStream(path)) {
            for (int index = 0; index < count; index++) {
                record.writeDelimitedTo(output);
            }
        }
        assertEquals(count, DatasetReader.read(path, ignored -> {}));
    }

    @Test
    void bindsTheOperationStreamToItsDataset() throws Exception {
        final var model = temporary.resolve("snapshot.pb");
        final var trace = temporary.resolve("operations.pb");
        write(model, header(), records());
        write(trace, Header.newBuilder().setVersion(2).setDatasetId(43).setResourceCount(205)
                .setStream(PlacementDatasetProtos.Stream.OPERATIONS).build(), List.of());
        assertThrows(IOException.class, () -> Dataset.load(model, trace));
    }

    private static Header header() {
        return Header.newBuilder().setVersion(2).setDatasetId(42).setStream(PlacementDatasetProtos.Stream.SNAPSHOT)
                .setResourceCount(205).setAllocCount(6).setConstraintCount(6).setCandidateCount(3)
                .setPlacementCount(3).setDimensionCount(20).setHostCount(200).setExtentBytes(67108864)
                .setAllocValueCount(12).setLimitCount(1).setLinkCount(2).setDiskPlanCount(1)
                .setDomainCount(1).setDeviceUseCount(2).setCandidateHostCount(201).build();
    }

    private static List<Record> records() {
        final var rows = new ArrayList<Record>();
        rows.add(alloc(1, new int[]{1, 2}, new double[]{32, 65536}));
        rows.add(alloc(2, new int[]{1, 2}, new double[]{16, 32768}));
        rows.add(alloc(3, new int[]{18, 19, 20}, new double[]{100000, 1, 80}));
        rows.add(alloc(4, new int[]{1, 2}, new double[]{1, 1024}));
        rows.add(alloc(5, new int[]{7}, new double[]{10}));
        rows.add(alloc(6, new int[]{1, 2}, new double[]{2, 512}));
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                .setCpuPower(2).addDomains(Domain.newBuilder().setLevel(3).setValue(1))).build());
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1)).build());
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(3).setKind(Kind.DISK).setParent(1)
                .setMedia(Media.NVME).setAlternativeMedia(Media.SSD).setAlternativeSpindles(0.5)).build());
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                .setCandidates(2).addLimits(Limit.newBuilder().setDomain(3).setMax(1).setHard(true))
                .addLinks(Link.newBuilder().setTarget(204).setDomain(1))
                .addLinks(Link.newBuilder().setTarget(2).setDomain(1)
                        .setRelation(PlacementDatasetProtos.Relation.SHARED_GROUP))).build());
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).setGroup(2)
                .addDisks(DiskPlan.newBuilder().setMedia(Media.SSD).setLayout(PlacementDatasetProtos.Layout.RAID4).setMinDevices(1).setMaxDevices(Integer.MAX_VALUE)
                        .setMinStripes(1).setMaxStripes(Integer.MAX_VALUE).setExtents(128).setMetadataExtents(1)
                        .setPerDeviceIopsCap(72))).build());
        rows.add(Record.newBuilder().setConstraints(Constraints.newBuilder().setId(6).setKind(Kind.GENERIC)).build());
        rows.add(Record.newBuilder().setCandidates(Candidates.newBuilder().setId(2).addHost(1)).build());
        final var all = Candidates.newBuilder().setId(3);
        for (int host = 1; host <= 200; host++) {
            all.addHost(host);
            rows.add(resource(host, 1, 1));
        }
        rows.add(Record.newBuilder().setCandidates(all).build());
        rows.add(resource(201, 2, 2));
        rows.add(resource(202, 3, 3));
        rows.add(resource(203, 4, 4));
        rows.add(resource(204, 5, 5));
        rows.add(resource(205, 0, 6));
        rows.add(Record.newBuilder().setPlacement(Placement.newBuilder().setResource(203).setHost(1).setNuma(201).setAlloc(6)).build());
        rows.add(Record.newBuilder().setPlacement(Placement.newBuilder().setResource(204).setHost(1).setNuma(201)
                .addDevices(DeviceUse.newBuilder().setDevice(202).setExtents(128).setSpindles(0.5).setIops(10))).build());
        rows.add(Record.newBuilder().setPlacement(Placement.newBuilder().setResource(205).setHost(1)
                .addDevices(DeviceUse.newBuilder().setDevice(202).setExtents(256))).build());
        return rows;
    }

    private static Record alloc(int id, int[] types, double[] amounts) {
        final var value = Alloc.newBuilder().setId(id);
        for (int index = 0; index < types.length; index++) {
            value.addType(types[index]).addAmount(amounts[index]);
        }
        return Record.newBuilder().setAlloc(value).build();
    }

    private static Record resource(int id, int alloc, int constraints) {
        return Record.newBuilder().setResource(Resource.newBuilder().setId(id).setAlloc(alloc).setConstraints(constraints)).build();
    }

    private static void write(Path path, Header header, List<Record> records) throws IOException {
        try (final var output = Files.newOutputStream(path)) {
            Record.newBuilder().setHeader(header).build().writeDelimitedTo(output);
            for (final var record : records) {
                record.writeDelimitedTo(output);
            }
        }
    }
}
