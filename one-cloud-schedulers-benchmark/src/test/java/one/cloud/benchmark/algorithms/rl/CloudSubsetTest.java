package one.cloud.benchmark.algorithms.rl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.DatasetReader;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Placement;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Stream;
import one.cloud.benchmark.runner.Scenario;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.candidates;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.writeTrace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSubsetTest {
    @TempDir
    Path temporary;

    @Test
    void retainsNativeInventoryConstraintsAndRawLifetimesWithoutRelaxingDependencies() throws Exception {
        final var source = fixture();
        final var output = temporary.resolve("subset");
        CloudSubset.export(source, output, 1, 42);
        final var data = Dataset.load(output.resolve("snapshot.pb"), output.resolve("operations.pb"));
        final var scenario = Scenario.from(data);
        assertEquals(1, data.hostCount);
        assertEquals(8, data.alloc.length);
        assertEquals(3, scenario.requests.length);
        assertArrayEquals(new int[]{4, 5, 6, 7, 7, 7, 7, 6, 5, 4}, data.operations.resource);
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 2, 2, 1, 1, 1}, data.operations.generation);
        assertArrayEquals(new long[]{1, 3, 5, 7, 9, 10, 12, 14, 16, 18}, data.operations.time);
        final var host = data.constraints[1];
        assertEquals(1.7, data.rules.cpuPower[host]);
        assertEquals(1, data.rules.parent[data.constraints[2]]);
        assertEquals(1, data.rules.parent[data.constraints[3]]);
        final var pod = data.constraints[4];
        assertEquals(1, data.rules.pinnedHost[pod]);
        assertEquals(42, data.rules.flags[pod]);
        assertTrue(data.candidates.allows(data.rules.candidates[pod], 1));
        assertEquals(5, data.rules.linkTarget[data.rules.linkOffset[pod]]);
        assertEquals(10, data.rules.group[pod]);
        final var shard = data.constraints[5];
        assertEquals(7, data.rules.diskFlags[data.rules.diskOffset[shard]]);
        assertEquals(37, data.rules.metadataExtents[data.rules.diskOffset[shard]]);
        assertEquals(123.0, data.rules.diskIopsCap[data.rules.diskOffset[shard]]);
        assertEquals(0, data.snapshot.resource.length);
        assertTrue(Files.readString(output.resolve("manifest.json")).contains("selectedHostIds"));
    }

    private Path fixture() throws Exception {
        final var source = temporary.resolve("source");
        final var records = new ArrayList<Record>();
        records.add(alloc(1, new int[]{1}, 8));
        records.add(alloc(2, new int[]{1, 11}, 8, 1));
        records.add(alloc(3, new int[]{1}, 1));
        records.add(alloc(4, new int[]{18, 19}, 1024, 1));
        for (int id = 1; id <= 13; id++) {
            final var rule = Constraints.newBuilder().setId(id).setKind(Kind.POD);
            if (id <= 3) {
                rule.setKind(Kind.HOST).setCpuPower(id == 3 ? 1.7 : 1.0);
            } else if (id == 4 || id == 5) {
                rule.setKind(id == 4 ? Kind.NUMA : Kind.DISK).setParent(3);
                if (id == 5) {
                    rule.setMedia(Media.SSD);
                }
            } else if (id == 6) {
                rule.setGroup(10).setPinnedHost(3).setCandidates(2).setFlags(42)
                        .addLinks(Link.newBuilder().setTarget(8).setDomain(1));
            } else if (id == 8) {
                rule.setKind(Kind.SHARD).setGroup(10).addDisks(DiskPlan.newBuilder()
                        .setMedia(Media.SSD).setLayout(Layout.RAID1).setExtents(100).setSpindles(.1)
                        .setFlags(7).setMetadataExtents(37).setPerDeviceIopsCap(123));
            } else if (id == 7 || id == 10) {
                rule.setGroup(20);
            } else if (id == 9 || id == 11) {
                rule.setGroup(id == 9 ? 30 : 40).addLinks(Link.newBuilder().setTarget(id == 9 ? 20 : 30)
                        .setDomain(1).setRelation(Relation.READY_GROUP));
            }
            records.add(rules(rule));
            records.add(resource(id, id == 3 ? 2 : id <= 4 ? 1 : id == 5 ? 4 : 3, id));
        }
        records.add(candidates(2, 1, 3));
        writeTrace(source, records.toArray(Record[]::new), 6, 7, 8, 9, 10, 11, 12, 13,
                -12, 12, -13, -12, -11, -10, -9, -8, -7, -6);
        final var model = new ArrayList<Record>();
        DatasetReader.read(source.resolve("snapshot.pb"), model::add);
        model.set(0, Record.newBuilder().setHeader(model.getFirst().getHeader().toBuilder()
                .setStream(Stream.SNAPSHOT).setPlacementCount(9)).build());
        for (int id = 6; id <= 13; id++) {
            model.add(Record.newBuilder().setPlacement(Placement.newBuilder().setResource(id)
                    .setHost(id == 7 ? 1 : 3).setNuma(id == 6 ? 4 : 0)).build());
        }
        model.add(Record.newBuilder().setPlacement(Placement.newBuilder().setResource(13).setHost(1)).build());
        try (final var stream = Files.newOutputStream(source.resolve("snapshot.pb"))) {
            for (final var record : model) {
                record.writeDelimitedTo(stream);
            }
        }
        return source;
    }
}
