package one.cloud.benchmark.algorithms.fitcount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Bundle;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Group;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Member;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Prior;
import one.cloud.benchmark.dataset.RolloutPriorProtos.Unit;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutTest {
    @TempDir
    Path temporary;
    private int files;

    @Test
    void commonSaturationPathsImproveTheScalarCounterexampleAndRepeatExactly() throws Exception {
        final var model = scalarModel();
        final var catalogue = new Request[]{new Request(4), new Request(5)};
        final var state = new Engine(model);
        try (final var baseline = new FitCount(state, 42, catalogue, 128, 256)) {
            state.schedule(baseline, new Request(3));
            assertEquals(1, state.placement(3).host());
        }
        final var prior = prior(model, 3, bundle(1, 1, 4), bundle(2, 1, 5));
        final var first = new Engine(model);
        final var second = new Engine(model);
        try (final var a = new Rollout(first, 42, catalogue, prior, 128, 256, 2, 32, 3);
                final var b = new Rollout(second, 42, catalogue, prior, 128, 256, 2, 32, 3)) {
            first.schedule(a, new Request(3));
            second.schedule(b, new Request(3));
            assertEquals(2, first.placement(3).host());
            assertEquals(first.placement(3).host(), second.placement(3).host());
            assertEquals(a.counters(), b.counters());
            assertEquals(192, a.counters().get("continuation_calls"));
            assertEquals(1, first.revision());
            assertEquals(2, first.available(1, 1));
            assertEquals(2, first.available(2, 1));
            assertNull(first.placement(4));
            assertNull(first.placement(5));
            assertEquals(0, a.counters().get("baseline_wins"));
            assertTrue(a.plannerCalls() > 2);
        }
        int baselineAdmissions = 0, alternativeAdmissions = 0;
        for (int path = 0; path < 8; path++) {
            final int[] original = {1, 3};
            final int[] alternative = {2, 2};
            for (int step = 0; step < 3; step++) {
                final int size = (path >>> step & 1) == 0 ? 2 : 3;
                baselineAdmissions += placeScalar(original, size);
                alternativeAdmissions += placeScalar(alternative, size);
            }
        }
        assertEquals(8, baselineAdmissions);
        assertEquals(11, alternativeAdmissions);
    }

    @Test
    void virtualJointStorageClosuresPreserveCardinalitiesAndCanonicalState() throws Exception {
        final var model = model(
                alloc(1, new int[]{1, 9}, 8, 100), alloc(2, new int[]{18, 19, 20}, 20, 4, 100),
                alloc(3, new int[]{1}, 1), alloc(4, new int[0]),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(1)
                        .addLinks(Link.newBuilder().setTarget(4).setDomain(1))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).setGroup(2)
                        .addDisks(DiskPlan.newBuilder().setMedia(Media.HDD).setExtents(3)
                                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 4, 4));
        final var manifest = Bundle.newBuilder().setId(1).setWeight(1)
                .addUnits(Unit.newBuilder().setId(1).addMembers(member(3)).addMembers(member(4)))
                .addGroups(Group.newBuilder().setSourceGroup(1).setCardinality(1))
                .addGroups(Group.newBuilder().setSourceGroup(2).setCardinality(1)).build();
        final var prior = prior(model, 2, manifest);
        assertEquals("", prior.unsupportedReason());
        final var path = prior.sample(42);
        assertEquals(2, path.length);
        final int first = path[0].resource(0), next = path[1].resource(0);
        assertNotEquals(first, next);
        final var bank = prior.model();
        final int profile = bank.constraints[first];
        assertEquals(path[0].resource(1), bank.rules.linkTarget[bank.rules.linkOffset[profile]]);
        assertNotEquals(model.rules.group[model.constraints[3]], bank.rules.group[profile]);
        assertNotEquals(bank.rules.group[profile], bank.rules.group[bank.constraints[next]]);
        final var current = new Request(3, 4);
        final var state = new Engine(model);
        try (final var algorithm = new Rollout(state, 42, new Request[]{current}, prior, 128, 256, 1, 2, 2)) {
            state.schedule(algorithm, current);
            assertEquals(1, state.revision());
            assertEquals(1, state.groupCount(1));
            assertEquals(1, state.groupCount(2));
            assertEquals(17, state.available(2, 18));
            assertEquals(4, algorithm.counters().get("continuation_admitted"));
            assertEquals(model.alloc.length, state.model().alloc.length);
            state.remove(current);
            assertEquals(20, state.available(2, 18));
            state.schedule(algorithm, current);
            assertEquals(3, state.revision());
            assertEquals(1, state.groupCount(1));
        }
    }

    @Test
    void declaredExternalDependenciesStayCanonicalAndUndeclaredReferencesFail() throws Exception {
        final var model = model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setTarget(1).setDomain(1).setRelation(Relation.READY_GROUP))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 3));
        final var manifest = bundle(1, 1, 3).toBuilder()
                .addGroups(Group.newBuilder().setSourceGroup(2).setCardinality(1)).addExternalGroups(1).build();
        final var prior = prior(model, 2, manifest);
        final var state = new Engine(model);
        try (final var algorithm = new Rollout(state, 42, new Request[]{new Request(3)}, prior, 128, 256, 1, 2, 2)) {
            state.schedule(algorithm, new Request(2));
            assertEquals(4, algorithm.counters().get("continuation_admitted"));
            assertEquals(1, state.groupCount(1));
            assertEquals(0, state.groupCount(2));
            assertNull(state.placement(3));
        }
        assertThrows(IOException.class, () -> prior(model, 2, manifest.toBuilder().clearExternalGroups().build()));
    }

    @Test
    void iidWholeBundleBoundaryStopsWithoutTruncationAndUsesFreshIdentities() throws Exception {
        final var model = scalarModel();
        final var manifest = bundle(1, 1, 4).toBuilder()
                .addUnits(Unit.newBuilder().setId(2).addMembers(member(5))).build();
        final var prior = prior(model, 3, manifest);
        for (int seed = 0; seed < 8; seed++) {
            final var path = prior.sample(seed);
            assertEquals(2, path.length);
            assertTrue(path[0].resource(0) >= model.alloc.length);
            assertNotEquals(path[0].resource(0), path[1].resource(0));
            assertArrayEquals(Arrays.stream(path).mapToInt(request -> request.resource(0)).toArray(),
                    Arrays.stream(prior.sample(seed)).mapToInt(request -> request.resource(0)).toArray());
        }
        final var weighted = prior(model, 3, bundle(1, 1, 4), manifest.toBuilder().setId(2).setWeight(3).build());
        assertArrayEquals(new int[]{model.alloc[4], model.alloc[5], model.alloc[4]},
                Arrays.stream(weighted.sample(4)).mapToInt(request -> weighted.model().alloc[request.resource(0)]).toArray());
        assertArrayEquals(new int[]{model.alloc[4], model.alloc[5]},
                Arrays.stream(weighted.sample(13)).mapToInt(request -> weighted.model().alloc[request.resource(0)]).toArray());
        final var oversized = prior(model, 1, manifest);
        assertEquals("bundle-exceeds-horizon", oversized.unsupportedReason());
        final var state = new Engine(model);
        try (final var algorithm = new Rollout(state, 42, new Request[]{new Request(4), new Request(5)},
                oversized, 128, 256, 2, 2, 1)) {
            state.schedule(algorithm, new Request(3));
            assertEquals(1, state.placement(3).host());
            assertEquals(1, algorithm.counters().get("fallback.prior-unsupported"));
            assertEquals(0, algorithm.counters().get("continuation_calls"));
            assertEquals("bundle-exceeds-horizon", algorithm.parameters().get("prior_unsupported"));
        }
    }

    @Test
    void malformedDigestWeightsPinsAndGroupsFailInsteadOfBeingDropped() throws Exception {
        final var model = scalarModel();
        final var raw = manifest(model, bundle(1, 1, 4));
        final var path = temporary.resolve("unsigned.pb");
        Files.write(path, raw.toByteArray());
        assertThrows(IOException.class, () -> RolloutPrior.load(path, model, 3));
        assertThrows(IOException.class, () -> prior(model, 3, bundle(1, Long.MAX_VALUE, 4), bundle(2, 1, 5)));
        final var pinned = bundle(1, 1, 4).toBuilder().setUnits(0, Unit.newBuilder().setId(1)
                .addMembers(member(4).toBuilder().setPinnedHost(1))).build();
        assertThrows(IOException.class, () -> prior(model, 3, pinned));
        assertThrows(IOException.class, () -> prior(model, 3, bundle(2, 1, 4), bundle(1, 1, 5)));
        final var grouped = model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2));
        final var incomplete = bundle(1, 1, 2).toBuilder()
                .addGroups(Group.newBuilder().setSourceGroup(1).setCardinality(2)).build();
        assertEquals("incomplete-source-group", prior(grouped, 3, incomplete).unsupportedReason());
        assertThrows(IOException.class, () -> prior(grouped, 3, incomplete.toBuilder().setGroups(0,
                Group.newBuilder().setSourceGroup(1).setCardinality(1)).build()));
    }

    @Test
    void declinedAttemptsAdvanceScenarioIndexAndTiesKeepTheBaseline() throws Exception {
        final var model = scalarModel();
        final var pair = Bundle.newBuilder().setId(2).setWeight(3)
                .addUnits(Unit.newBuilder().setId(1).addMembers(member(4)))
                .addUnits(Unit.newBuilder().setId(2).addMembers(member(5))).build();
        final var prior = prior(model, 3, bundle(1, 1, 4), pair);
        final var state = new Engine(model);
        try (final var algorithm = new Rollout(state, 0, new Request[]{new Request(4)}, prior, 128, 256, 1, 2, 3)) {
            state.schedule(algorithm, new Request(3, 4, 5));
            assertEquals(0, state.revision());
            state.schedule(algorithm, new Request(3));
            final var counters = algorithm.counters();
            assertEquals(2, counters.get("attempts"));
            assertEquals(1, counters.get("declines"));
            assertFalse(counters.containsKey("path.0.0.creates"));
            assertEquals(3, counters.get("path.1.0.creates"));
            assertEquals(3, counters.get("path.1.1.creates"));
            assertEquals(1, counters.get("baseline_wins"));
            assertNotNull(state.placement(3));
        }
    }

    private RolloutPrior prior(ClusterSpec model, int horizon, Bundle... bundles) throws IOException {
        final var value = manifest(model, bundles);
        final var signed = value.toBuilder().setSha256(ByteString.copyFrom(RolloutPrior.canonicalDigest(value))).build();
        final var path = temporary.resolve("prior-" + files++ + ".pb");
        Files.write(path, signed.toByteArray());
        return RolloutPrior.load(path, model, horizon);
    }

    private static Prior manifest(ClusterSpec model, Bundle... bundles) {
        return Prior.newBuilder().setVersion(RolloutPrior.VERSION).setDatasetId(model.id)
                .addAllBundles(Arrays.asList(bundles)).build();
    }

    private static Bundle bundle(int id, long weight, int resource) {
        return Bundle.newBuilder().setId(id).setWeight(weight)
                .addUnits(Unit.newBuilder().setId(1).addMembers(member(resource))).build();
    }

    private static Member member(int resource) {
        return Member.newBuilder().setSourceResource(resource).build();
    }

    private static ClusterSpec scalarModel() throws IOException {
        return model(
                alloc(1, new int[]{1}, 2), alloc(2, new int[]{1}, 3),
                alloc(3, new int[]{1}, 1), alloc(4, new int[]{1}, 2), alloc(5, new int[]{1}, 3),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 1), resource(3, 3, 2), resource(4, 4, 2), resource(5, 5, 2));
    }

    private static int placeScalar(int[] free, int size) {
        for (int host = 0; host < free.length; host++) {
            if (free[host] >= size) {
                free[host] -= size;
                return 1;
            }
        }
        return 0;
    }
}
