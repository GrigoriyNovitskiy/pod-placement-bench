package one.cloud.benchmark.runner;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.model.ClusterSpec;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static one.cloud.benchmark.testsupport.TestModel.trace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioTest {
    @Test
    void gpuModelChangesScenarioIdentityWithoutChangingItsEvents() throws Exception {
        final var source = trace(records(pod(1)), 2, -2);
        final var defaults = Scenario.from(source);
        final var aggregate = Scenario.from(source, ClusterSpec.GpuMode.AGGREGATE);
        final var devices = Scenario.from(source, ClusterSpec.GpuMode.DEVICES_20);
        assertEquals(ClusterSpec.GpuMode.DEVICES_20, defaults.model.gpuMode);
        assertEquals(defaults.digest, devices.digest);
        assertNotEquals(aggregate.digest, devices.digest);
        assertEquals(aggregate.sourceOpsDigest, devices.sourceOpsDigest);
        assertArrayEquals(aggregate.events, devices.events);
        assertArrayEquals(aggregate.checkpoints, devices.checkpoints);
        assertEquals(ClusterSpec.GpuMode.DEVICES_20, devices.model.gpuMode);
        assertEquals(devices.digest, Scenario.from(source, ClusterSpec.GpuMode.DEVICES_20).digest);
        assertThrows(NullPointerException.class, () -> Scenario.from(source, null));
    }

    @Test
    void expandsPartialAtomicChurnThroughReadyAndSharedConsumers() throws Exception {
        for (final var relation : new Relation[]{Relation.READY_GROUP, Relation.SHARED_GROUP}) {
            final var rows = records(shard(1), pod(2).addLinks(colocate(2)),
                    pod(3).addLinks(group(1, relation)), pod(4));
            final var source = trace(rows, 2, 4, 3, 5, -3, 3, -5, -3, -4, -2);
            final var scenario = Scenario.from(source);
            assertEquals(3, scenario.requests.length);
            assertEquals(2, scenario.requests[0].size());
            assertEquals(2, scenario.requests[0].resource(0));
            assertEquals(3, scenario.requests[0].resource(1));
            assertArrayEquals(new int[]{1, 2, 3, -2, -1, 1, 2, -3, -2, -1}, scenario.events);
            assertArrayEquals(new int[]{3, 7}, scenario.checkpoints);
            assertEquals(10, scenario.rawOperations);
            assertEquals(1, scenario.partialChurn);
            assertEquals(1, scenario.expandedRestarts);
            assertEquals(scenario.digest, Scenario.from(source).digest);
            assertEquals(64, scenario.sourceOpsDigest.length());
            for (int index = 0; index < source.operations.time.length; index++) {
                source.operations.time[index] += 10;
            }
            final var shifted = Scenario.from(source);
            assertArrayEquals(scenario.events, shifted.events);
            assertNotEquals(scenario.sourceOpsDigest, shifted.sourceOpsDigest);
            assertNotEquals(scenario.digest, shifted.digest);
        }
    }

    @Test
    void ignoresOmittedStaticColocationConsumersButRequiresIncludedTargets() throws Exception {
        final var rows = records(shard(1), pod(2).addLinks(colocate(2)), pod(3));
        final var scenario = Scenario.from(trace(rows, 2, 4, -4, -2));
        assertEquals(2, scenario.requests.length);
        assertEquals(1, scenario.requests[0].size());
        assertEquals(2, scenario.requests[0].resource(0));
        assertArrayEquals(new int[]{1, 2, -2, -1}, scenario.events);
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(rows, 3, -3)));
    }

    @Test
    void treatsThePendingAtomicUnitAsReadyInsideItsOwnGroup() throws Exception {
        final var rows = records(shard(1), pod(2).addLinks(colocate(2)).addLinks(group(1, Relation.READY_GROUP)), shard(1));
        final var scenario = Scenario.from(trace(rows, 2, 4, 3, -3, -4, -2));
        assertArrayEquals(new int[]{2, 1, -1, -2}, scenario.events);
        assertArrayEquals(new int[]{2}, scenario.checkpoints);
        final var internal = records(shard(1), pod(2).addLinks(colocate(2)).addLinks(group(1, Relation.READY_GROUP)));
        assertArrayEquals(new int[]{1, -1}, Scenario.from(trace(internal, 2, 3, -3, -2)).events);
    }

    @Test
    void rejectsMissingGroupMembersAndTrueContractedCycles() throws Exception {
        final var missing = records(pod(1), pod(1), pod(2).addLinks(group(1, Relation.READY_GROUP)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(missing, 2, 4, -4, -2)));
        final var generic = records(pod(1), Constraints.newBuilder().setKind(Kind.GENERIC).setGroup(1),
                pod(2).addLinks(group(1, Relation.READY_GROUP)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(generic, 2, 4, -4, -2)));
        final var empty = records(pod(1).addLinks(group(9, Relation.READY_GROUP)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(empty, 2, -2)));
        final var cycle = records(shard(1), pod(2).addLinks(colocate(2)).addLinks(group(3, Relation.READY_GROUP)),
                pod(3).addLinks(group(1, Relation.READY_GROUP)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(cycle, 2, 4, 3, -3, -4, -2)));
    }

    @Test
    void validatesPhasesGenerationsResourcesAndSourceMode() throws Exception {
        final var rows = records(pod(1), pod(2));
        for (final var malformed : new int[][]{
                {-2}, {2}, {2, 2, -2, -2}, {2, -2, 3, -3},
                {2, 3, -2, -3, 2, -2, 2, 3, -2, -3}, {1, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(rows, malformed)), Arrays.toString(malformed));
        }
        final var wrongGeneration = trace(rows, 2, -2, 2, -2);
        wrongGeneration.operations.generation[2] = 7;
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(wrongGeneration));
        final var wrongDelete = trace(rows, 2, -2);
        wrongDelete.operations.generation[1] = 2;
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(wrongDelete));
        final var wrongTime = trace(rows, 2, -2);
        wrongTime.operations.time[1] = 0;
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(wrongTime));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(rows, false, false, 2, -2)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(trace(rows, true, true, 2, -2)));
        assertThrows(IllegalArgumentException.class, () -> Scenario.from(null));
    }

    @Test
    void acceptsADeclaredEmptySyntheticTrace() throws Exception {
        final var scenario = Scenario.from(trace(records(pod(1))));
        assertEquals(0, scenario.rawOperations);
        assertEquals(0, scenario.requests.length);
        assertArrayEquals(new int[0], scenario.events);
        assertArrayEquals(new int[0], scenario.checkpoints);
        assertEquals(0, scenario.partialChurn);
        assertEquals(0, scenario.expandedRestarts);
        assertEquals(64, scenario.digest.length());
    }

    @Test
    void compilesLargeGroupBarriersWithoutExpandingOwnerConsumerPairs() throws Exception {
        final int owners = 2000;
        final int consumers = 2000;
        final var rows = new ArrayList<Record>();
        rows.add(alloc(1, new int[]{1, 2}, 16, 1024));
        rows.add(alloc(2, new int[]{1, 2}, 1, 1));
        rows.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        rows.add(rules(shard(1).setId(2)));
        rows.add(rules(pod(2).setId(3).addLinks(group(1, Relation.READY_GROUP))));
        rows.add(resource(1, 1, 1));
        final var events = new int[2 * (owners + consumers)];
        for (int index = 0; index < owners + consumers; index++) {
            final int id = index + 2;
            rows.add(resource(id, 2, index < owners ? 2 : 3));
            events[index] = id;
            events[events.length - index - 1] = -id;
        }
        final var scenario = Scenario.from(trace(rows.toArray(Record[]::new), events));
        assertEquals(owners + consumers, scenario.requests.length);
        assertEquals(events.length, scenario.events.length);
        assertEquals(owners + consumers, scenario.checkpoints[0]);
        assertEquals(owners + consumers, scenario.events[owners + consumers - 1]);
    }

    private static Record[] records(Constraints.Builder... requests) {
        final var rows = new ArrayList<Record>();
        rows.add(alloc(1, new int[]{1, 2}, 16, 1024));
        rows.add(alloc(2, new int[]{1, 2}, 1, 1));
        rows.add(rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)));
        rows.add(resource(1, 1, 1));
        for (int index = 0; index < requests.length; index++) {
            final int id = index + 2;
            rows.add(rules(requests[index].setId(id)));
            rows.add(resource(id, 2, id));
        }
        return rows.toArray(Record[]::new);
    }

    private static Constraints.Builder pod(int group) {
        return Constraints.newBuilder().setKind(Kind.POD).setGroup(group);
    }

    private static Constraints.Builder shard(int group) {
        return Constraints.newBuilder().setKind(Kind.SHARD).setGroup(group);
    }

    private static Link.Builder colocate(int resource) {
        return Link.newBuilder().setRelation(Relation.COLOCATE).setDomain(1).setTarget(resource);
    }

    private static Link.Builder group(int group, Relation relation) {
        return Link.newBuilder().setRelation(relation).setDomain(1).setTarget(group);
    }
}
