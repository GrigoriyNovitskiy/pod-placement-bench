package one.cloud.benchmark.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Domain;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.model.Placement;
import one.cloud.benchmark.model.PlacementRequest;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.candidates;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementStateTest {
    @Test
    void commitsCombinedBudgetsAndRestoresWholeUnits() throws Exception {
        final var state = new PlacementState(model(
                alloc(1, new int[]{1}, 3), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2)));
        final var request = new PlacementRequest(3, 4);
        final var notifications = new ArrayList<Integer>();
        final int[] offsets = {0};
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest unit) {
                assertFalse(context.canPlace(unit, plan(3, 1), plan(4, 1)));
                assertEquals(3, context.available(1, 1));
                assertEquals(0, context.groupCount(1));
                assertEquals(0, context.revision());
                assertTrue(context.canPlace(unit, plan(3, 1), plan(4, 2)));
                context.place(unit, plan(4, 2), new Placement(3, 1, 0, offsets, new int[0]));
            }

            @Override
            public void placed(Placement plan) {
                assertNotNull(context.placement(3));
                assertNotNull(context.placement(4));
                assertEquals(2, context.groupCount(1));
                assertEquals(1, context.revision());
                assertThrows(IllegalStateException.class, () -> state.remove(request));
                notifications.add(plan.resource());
            }

            @Override
            public void removed(Placement plan) {
                assertNull(context.placement(3));
                assertNull(context.placement(4));
                assertEquals(0, context.groupCount(1));
                assertEquals(2, context.revision());
                notifications.add(-plan.resource());
            }
        };
        state.schedule(algorithm, request);
        offsets[0] = 99;
        assertEquals(0, state.placement(3).diskOffsets()[0]);
        assertEquals(1, state.available(1, 1));
        assertEquals(1, state.available(2, 1));
        assertEquals(1, state.capacityRevision(1));
        assertEquals(1, state.capacityRevision(2));
        assertThrows(IllegalArgumentException.class, () -> state.remove(new PlacementRequest(3, 4)));
        state.remove(request);
        assertEquals(3, state.available(1, 1));
        assertEquals(3, state.available(2, 1));
        assertEquals(2, state.capacityRevision(1));
        assertEquals(2, state.capacityRevision(2));
        assertEquals(List.of(3, 4, -3, -4), notifications);
    }

    @Test
    void appliesHardLimitsToTheWholeUnitAndKeepsSoftLimitsFeasible() throws Exception {
        final var state = new PlacementState(model(
                alloc(1, new int[]{1}, 4), alloc(2, new int[]{1}, 2), alloc(3, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                        .addDomains(Domain.newBuilder().setLevel(3).setValue(7))),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.NUMA).setParent(1).setOrdinal(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.NUMA).setParent(1).setOrdinal(2)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(2).setMax(1).setHard(true))
                        .addLimits(Limit.newBuilder().setDomain(3).setMax(1))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.POD).setGroup(2)
                        .addLimits(Limit.newBuilder().setDomain(4).setMax(1).setHard(true))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3),
                resource(5, 3, 4), resource(6, 3, 4), resource(7, 3, 5)));
        final var unit = new PlacementRequest(5, 6);
        final var missingDomain = new PlacementRequest(7);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                if (request == unit) {
                    assertFalse(context.canPlace(request, plan(5, 1, 3), plan(6, 1, 3)));
                    context.place(request, plan(5, 1, 3), plan(6, 1, 4));
                } else {
                    assertFalse(context.canPlace(request, plan(7, 2)));
                }
            }
        };
        state.schedule(algorithm, unit, missingDomain);
        assertEquals(2, state.domainCount(1, 1, 1));
        assertEquals(1, state.domainCount(1, 2, 3));
        assertEquals(1, state.domainCount(1, 2, 4));
        assertEquals(2, state.domainCount(1, 3, 7));
        assertEquals(0, state.domainCount(1, 4, 7));
        state.remove(unit);
        assertEquals(0, state.domainCount(1, 3, 7));
    }

    @Test
    void requiresReadyGroupsAndProtectsLiveDependentsOnRemoval() throws Exception {
        final var state = new PlacementState(model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setRelation(Relation.COLOCATE).setTarget(3).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(1).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(1))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2), resource(5, 2, 3)));
        final var first = new PlacementRequest(3);
        final var second = new PlacementRequest(4);
        final var dependent = new PlacementRequest(5);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                if (request == dependent) {
                    assertFalse(context.canPlace(request, plan(5, 2)));
                    if (context.groupCount(1) == 2) {
                        context.place(request, plan(5, 1));
                    } else {
                        assertFalse(context.canPlace(request, plan(5, 1)));
                    }
                } else {
                    context.place(request, plan(request.resource(0), request == first ? 1 : 2));
                }
            }
        };
        state.schedule(algorithm, first, dependent);
        assertNull(state.placement(5));
        state.schedule(algorithm, second, dependent);
        assertNotNull(state.placement(5));
        assertThrows(IllegalStateException.class, () -> state.remove(first));
        assertThrows(IllegalStateException.class, () -> state.remove(second));
        assertEquals(3, state.revision());
        state.remove(dependent);
        state.remove(first);
        state.remove(second);
        assertEquals(0, state.groupCount(1));
        assertEquals(0, state.groupCount(2));
        assertEquals(8, state.available(1, 1));
        assertEquals(8, state.available(2, 1));
    }

    @Test
    void resolvesColocationInsideAnAtomicUnitAndHonorsMasksAndPins() throws Exception {
        final var state = new PlacementState(model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2).setCandidates(2)
                        .addLinks(Link.newBuilder().setTarget(3).setDomain(1))),
                candidates(2, 1), resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3)));
        final var unit = new PlacementRequest(4, 3);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                assertFalse(context.canPlace(request, plan(4, 1), plan(3, 2)));
                assertFalse(context.canPlace(request, plan(4, 2), plan(3, 2)));
                context.place(request, plan(3, 1), plan(4, 1));
            }
        };
        state.schedule(algorithm, unit);
        assertEquals(1, state.revision());
        assertEquals(1, state.capacityRevision(1));
        state.remove(unit);
        assertEquals(8, state.available(1, 1));
    }

    @Test
    void enforcesExactWindowsOrderAndOwnerThread() throws Exception {
        final var state = scalarState();
        final var first = new PlacementRequest(2);
        final var second = new PlacementRequest(3);
        final var last = new PlacementRequest(4);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                throw new AssertionError("Batch override must be used");
            }

            @Override
            public void schedule(PlacementRequest[] requests) {
                assertThrows(IllegalArgumentException.class,
                        () -> context.canPlace(new PlacementRequest(2), plan(2, 1)));
                assertThrows(IllegalArgumentException.class, () -> context.canPlace(first));
                context.place(second, plan(3, 1));
                assertThrows(IllegalArgumentException.class, () -> context.place(first, plan(2, 1)));
                assertThrows(IllegalArgumentException.class, () -> context.place(second, plan(3, 1)));
            }
        };
        assertThrows(IllegalArgumentException.class, () -> state.schedule(algorithm, first, first));
        state.schedule(algorithm, first, second, last);
        assertNull(state.placement(2));
        assertNotNull(state.placement(3));
        assertNull(state.placement(4));
        assertThrows(IllegalArgumentException.class, () -> state.place(last, plan(4, 1)));
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(state::revision).join());
    }

    @Test
    void callbackFailuresAbortEvenIfTheAlgorithmCatchesThem() throws Exception {
        final var state = scalarState();
        final var first = new PlacementRequest(2);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                assertThrows(IllegalArgumentException.class, () -> context.place(request, plan(2, 1)));
            }

            @Override
            public void placed(Placement plan) {
                throw new IllegalArgumentException("Broken cache notification");
            }
        };
        final var failure = assertThrows(IllegalStateException.class, () -> state.schedule(algorithm, first));
        assertEquals("Broken cache notification", failure.getCause().getMessage());
        assertNotNull(state.placement(2));
        assertThrows(IllegalStateException.class, () -> state.remove(first));
        assertThrows(IllegalStateException.class, () -> state.schedule(algorithm, new PlacementRequest(3)));
    }

    @Test
    void restoresSparseDomainCountersAfterGrowthAndUnorderedRemoval() throws Exception {
        final var records = new ArrayList<Record>(List.of(alloc(1, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1))));
        final var requests = new PlacementRequest[32];
        for (int host = 1; host <= requests.length; host++) {
            records.add(resource(host, 1, 1));
            records.add(resource(host + requests.length, 1, 2));
            requests[host - 1] = new PlacementRequest(host + requests.length);
        }
        final var state = new PlacementState(model(records.toArray(Record[]::new)));
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                final int resource = request.resource(0);
                context.place(request, plan(resource, resource - requests.length));
            }
        };
        state.schedule(algorithm, requests);
        for (int step = 0; step < requests.length; step++) {
            state.remove(requests[step * 13 % requests.length]);
            assertEquals(requests.length - step - 1, state.groupCount(1));
            for (int host = 1; host <= requests.length; host++) {
                final int expected = state.placement(host + requests.length) == null ? 0 : 1;
                assertEquals(expected, state.domainCount(1, 1, host));
                assertEquals(expected, state.domainCount(1, 2, host));
            }
        }
    }

    @Test
    void zeroDemandChangesEligibilityWithoutInvalidatingPhysicalCapacity() throws Exception {
        final var state = new PlacementState(model(alloc(1, new int[]{1}, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 0, 2)));
        final var request = new PlacementRequest(2);
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                context.place(request, plan(2, 1));
            }
        };
        state.schedule(algorithm, request);
        assertEquals(1, state.revision());
        assertEquals(0, state.capacityRevision(1));
        assertEquals(1, state.domainCount(1, 1, 1));
        state.remove(request);
        assertEquals(2, state.revision());
        assertEquals(0, state.capacityRevision(1));
        assertEquals(0, state.domainCount(1, 1, 1));
        assertEquals(8, state.available(1, 1));
    }

    @Test
    void tracksOnlyActiveConsumersWithLargeStaticFanoutAndInternalLinks() throws Exception {
        final var records = new ArrayList<Record>(List.of(alloc(1, new int[]{1}, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setRelation(Relation.COLOCATE).setTarget(2).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(1).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(1))),
                resource(1, 1, 1), resource(2, 0, 2)));
        for (int resource = 3; resource < 100003; resource++) {
            records.add(resource(resource, 0, 3));
        }
        final var state = new PlacementState(model(records.toArray(Record[]::new)));
        final var algorithm = new PlacementAlgorithm(state, 1) {
            @Override
            public void schedule(PlacementRequest request) {
                final var plans = new Placement[request.size()];
                for (int member = 0; member < plans.length; member++) {
                    plans[member] = plan(request.resource(member), 1);
                }
                context.place(request, plans);
            }
        };
        final var joint = new PlacementRequest(3, 4, 2);
        state.schedule(algorithm, joint);
        state.remove(joint);
        final var owner = new PlacementRequest(2);
        final var consumer = new PlacementRequest(3);
        for (int cycle = 0; cycle < 10; cycle++) {
            state.schedule(algorithm, owner);
            state.schedule(algorithm, consumer);
            assertThrows(IllegalStateException.class, () -> state.remove(owner));
            state.remove(consumer);
            state.remove(owner);
        }
        assertEquals(0, state.groupCount(1));
        assertEquals(0, state.groupCount(2));
        assertEquals(0, state.capacityRevision(1));
    }

    @Test
    void sharedConsumersRequireOnlyTheLastOwnerInTheirDomain() throws Exception {
        for (final int level : new int[]{1, 3}) {
            final var state = new PlacementState(model(alloc(1, new int[]{1}, 8),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                            .addDomains(Domain.newBuilder().setLevel(3).setValue(7))),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1)),
                    rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                            .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(level))),
                    resource(1, 1, 1), resource(2, 1, 1), resource(3, 0, 2), resource(4, 0, 2), resource(5, 0, 3)));
            final var first = new PlacementRequest(3);
            final var second = new PlacementRequest(4);
            final var consumer = new PlacementRequest(5);
            final var algorithm = new PlacementAlgorithm(state, 1) {
                @Override
                public void schedule(PlacementRequest request) {
                    final int host = request == second && level == 3 ? 2 : 1;
                    context.place(request, plan(request.resource(0), host));
                }
            };
            state.schedule(algorithm, first, second, consumer);
            state.remove(first);
            assertThrows(IllegalStateException.class, () -> state.remove(second));
            state.remove(consumer);
            state.remove(second);
            assertEquals(0, state.groupCount(1));
            assertEquals(0, state.groupCount(2));
        }
    }

    private static PlacementState scalarState() throws Exception {
        return new PlacementState(model(alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2)));
    }

    private static Placement plan(int resource, int host) {
        return plan(resource, host, 0);
    }

    private static Placement plan(int resource, int host, int numa) {
        return new Placement(resource, host, numa, new int[]{0}, new int[0]);
    }
}
