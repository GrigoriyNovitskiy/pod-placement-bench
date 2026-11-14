package one.cloud.benchmark.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Domain;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.DiskPlan;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Limit;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

import static one.cloud.benchmark.testsupport.TestModel.alloc;
import static one.cloud.benchmark.testsupport.TestModel.candidates;
import static one.cloud.benchmark.testsupport.TestModel.model;
import static one.cloud.benchmark.testsupport.TestModel.resource;
import static one.cloud.benchmark.testsupport.TestModel.rules;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {
    @Test
    void optionalScalarProjectionDistinguishesPhysicalRejectionFromMalformedPlans() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 1), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 1, 2)));
        assertEquals(List.of(new Engine.ScalarDebit(1, 1, 1)),
                state.tryCpuRamDebits(new Assignment[]{plan(3, 1)}));
        assertNull(state.tryCpuRamDebits(new Assignment[]{plan(2, 1)}));
        assertThrows(IllegalArgumentException.class,
                () -> state.tryCpuRamDebits(new Assignment[]{plan(3, 2)}));
        assertEquals(1, state.available(1, 1));
        assertEquals(0, state.revision());
    }

    @Test
    void gpuFloatExportDemandConsumesExactSharesAndReleasesWithoutDrift() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 1), alloc(2, new int[]{11}, (double) .2f),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2),
                resource(5, 2, 2), resource(6, 2, 2)));
        final int gpu = state.model().gpus.first(1, 11);
        final var planner = new GreedyPlanner(state);
        final var requests = new Request[]{new Request(2), new Request(3), new Request(4), new Request(5), new Request(6)};
        assertEquals(5, planner.fitCount(requests[0], 1, 10));
        final var algorithm = greedy(state);
        for (int index = 0; index < requests.length; index++) {
            state.schedule(algorithm, requests[index]);
            assertNotNull(state.placement(requests[index].resource(0)));
            assertEquals(20 - (index + 1) * 4, state.gpuAvailable(gpu));
            assertEquals(1 - (index + 1) / 5.0, state.available(1, 11), 1e-12);
        }
        assertEquals(0, planner.fitCount(requests[0], 1, 10));
        for (int index = requests.length - 1; index >= 0; index--) {
            state.remove(requests[index]);
            assertEquals(20 - index * 4, state.gpuAvailable(gpu));
            assertEquals(1 - index / 5.0, state.available(1, 11), 1e-12);
        }
        assertEquals(5, planner.fitCount(requests[0], 1, 10));
    }

    @Test
    void gpuDevicesRejectFragmentedSharesAndReleaseTheSelectedCard() throws Exception {
        for (final var mode : ClusterSpec.GpuMode.values()) {
            final var records = new ArrayList<Record>(List.of(alloc(1, new int[]{11}, 3),
                    alloc(2, new int[]{11}, .8), alloc(3, new int[]{11}, .6),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)), resource(1, 1, 1),
                    resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2), resource(5, 3, 2)));
            final var state = new Engine(model(mode, records.toArray(Record[]::new)));
            final var first = new Request(2);
            final var second = new Request(3);
            final var third = new Request(4);
            final var fragmented = new Request(5);
            final var algorithm = greedy(state);
            state.schedule(algorithm, first, second, third);
            assertEquals(.6, state.available(1, 11), 1e-12);
            state.schedule(algorithm, fragmented);
            if (mode == ClusterSpec.GpuMode.AGGREGATE) {
                assertNotNull(state.placement(5));
                continue;
            }
            assertNull(state.placement(5));
            final int firstGpu = state.model().gpus.first(1, 11);
            for (int device = firstGpu; device < state.model().gpus.end(1, 11); device++) {
                assertEquals(4, state.gpuAvailable(device));
            }
            assertEquals(3, state.revision());
            state.remove(first);
            assertEquals(20, state.gpuAvailable(firstGpu));
            assertEquals(4, state.gpuAvailable(firstGpu + 1));
            assertEquals(4, state.gpuAvailable(firstGpu + 2));
            state.schedule(algorithm, fragmented);
            assertArrayEquals(new int[]{firstGpu}, state.placement(5).gpus());
            assertEquals(8, state.gpuAvailable(firstGpu));
        }
    }

    @Test
    void gpuAtomicValidationAccumulatesDemandAndRollsBackLateFailures() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11}, 2), alloc(2, new int[]{11}, .6),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)
                        .addLimits(Limit.newBuilder().setDomain(1).setMax(1).setHard(true))),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 3)));
        final int gpu = state.model().gpus.first(1, 11);
        final var rejected = new Request(2, 3);
        final var accepted = new Request(2, 4);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                final int second = request.resource(1);
                assertFalse(context.canPlace(request, gpuPlan(2, 1, gpu), gpuPlan(second, 1, gpu)));
                final var plans = new Assignment[]{gpuPlan(2, 1, gpu), gpuPlan(second, 1, gpu + 1)};
                if (request == rejected) {
                    assertFalse(context.canPlace(request, plans));
                    assertThrows(IllegalArgumentException.class, () -> context.place(request, plans));
                    assertEquals(0, context.revision());
                    assertEquals(0, context.groupCount(1));
                    assertEquals(2, context.available(1, 11));
                    assertEquals(20, context.gpuAvailable(gpu));
                    assertEquals(20, context.gpuAvailable(gpu + 1));
                } else {
                    assertTrue(context.canPlace(request, plans));
                    context.place(request, plans);
                }
            }
        };
        state.schedule(algorithm, rejected);
        state.schedule(algorithm, accepted);
        assertEquals(8, state.gpuAvailable(gpu));
        assertEquals(8, state.gpuAvailable(gpu + 1));
        assertEquals(1, state.capacityRevision(1));
        state.remove(accepted);
        assertEquals(20, state.gpuAvailable(gpu));
        assertEquals(20, state.gpuAvailable(gpu + 1));
        assertEquals(2, state.capacityRevision(1));
    }

    @Test
    void gpuAssignmentsValidateIdentityAndSnapshotBuffersAcrossResourceTypes() throws Exception {
        final var state = new Engine(model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{11, 17}, 2, 1), alloc(2, new int[]{11}, 2),
                alloc(3, new int[]{11, 17}, .6, .25),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 3, 2), resource(5, 0, 2)));
        final int nvidia = state.model().gpus.first(1, 11);
        final int intel = state.model().gpus.first(1, 17);
        final int otherHost = state.model().gpus.first(2, 11);
        final var whole = new Request(3);
        final var mixed = new Request(4);
        final var zero = new Request(5);
        final int[] selected = {nvidia, intel};
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                if (request == whole) {
                    assertThrows(IllegalArgumentException.class, () -> context.canPlace(request, plan(3, 1)));
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(3, 1, nvidia)));
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(3, 1, nvidia, nvidia)));
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(3, 1, nvidia, intel)));
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(3, 1, nvidia, otherHost)));
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(3, 1, nvidia, Integer.MAX_VALUE)));
                    context.place(request, gpuPlan(3, 1, nvidia, nvidia + 1));
                } else if (request == zero) {
                    assertThrows(IllegalArgumentException.class,
                            () -> context.canPlace(request, gpuPlan(5, 1, intel)));
                } else if (context.gpuAvailable(nvidia) == 0) {
                    assertFalse(context.canPlace(request, gpuPlan(4, 1, selected)));
                } else {
                    context.place(request, gpuPlan(4, 1, selected));
                }
            }
        };
        state.schedule(algorithm, whole, mixed, zero);
        assertEquals(0, state.gpuAvailable(nvidia));
        assertEquals(0, state.gpuAvailable(nvidia + 1));
        assertEquals(20, state.gpuAvailable(intel));
        assertNull(state.placement(4));
        state.remove(whole);
        state.schedule(algorithm, mixed);
        selected[0] = otherHost;
        assertArrayEquals(new int[]{nvidia, intel}, state.placement(4).gpus());
        assertEquals(8, state.gpuAvailable(nvidia));
        assertEquals(15, state.gpuAvailable(intel));
        state.remove(mixed);
        assertEquals(20, state.gpuAvailable(nvidia));
        assertEquals(20, state.gpuAvailable(intel));
    }

    @Test
    void commitsCombinedBudgetsAndRestoresWholeUnits() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 3), alloc(2, new int[]{1}, 2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2)));
        final var request = new Request(3, 4);
        final var notifications = new ArrayList<Integer>();
        final int[] offsets = {0};
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request unit) {
                assertFalse(context.canPlace(unit, plan(3, 1), plan(4, 1)));
                assertEquals(3, context.available(1, 1));
                assertEquals(0, context.groupCount(1));
                assertEquals(0, context.revision());
                assertTrue(context.canPlace(unit, plan(3, 1), plan(4, 2)));
                context.place(unit, plan(4, 2), new Assignment(3, 1, 0, offsets, new int[0]));
            }

            @Override
            public void capacityChanged(int host) {
                if (context.revision() == 1) {
                    assertNotNull(context.placement(3));
                    assertNotNull(context.placement(4));
                    assertEquals(2, context.groupCount(1));
                } else {
                    assertNull(context.placement(3));
                    assertNull(context.placement(4));
                    assertEquals(0, context.groupCount(1));
                    assertEquals(2, context.revision());
                }
                assertThrows(IllegalStateException.class, () -> state.remove(request));
                notifications.add(host);
            }
        };
        state.schedule(algorithm, request);
        offsets[0] = 99;
        assertEquals(0, state.placement(3).diskOffsets()[0]);
        assertEquals(1, state.available(1, 1));
        assertEquals(1, state.available(2, 1));
        assertEquals(1, state.capacityRevision(1));
        assertEquals(1, state.capacityRevision(2));
        assertEquals(List.of(1, 2), notifications.stream().sorted().toList());
        assertThrows(IllegalArgumentException.class, () -> state.remove(new Request(3, 4)));
        state.remove(request);
        assertEquals(3, state.available(1, 1));
        assertEquals(3, state.available(2, 1));
        assertEquals(2, state.capacityRevision(1));
        assertEquals(2, state.capacityRevision(2));
        assertEquals(List.of(1, 1, 2, 2), notifications.stream().sorted().toList());
    }

    @Test
    void capacityNotificationsDeduplicateHostsAndIgnoreZeroDemand() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 3), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 0, 2)));
        final var compound = new Request(2, 3);
        final var empty = new Request(4);
        final var notifications = new ArrayList<Integer>();
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                if (request == compound) {
                    context.place(request, plan(2, 1), plan(3, 1));
                } else {
                    context.place(request, plan(4, 1));
                }
            }

            @Override
            public void capacityChanged(int host) {
                notifications.add(host);
            }
        };
        state.schedule(algorithm, compound);
        assertEquals(List.of(1), notifications);
        assertEquals(1, state.capacityRevision(1));
        state.schedule(algorithm, empty);
        assertEquals(2, state.revision());
        assertEquals(3, state.groupCount(1));
        assertEquals(1, state.capacityRevision(1));
        assertEquals(List.of(1), notifications);
        state.remove(empty);
        assertEquals(3, state.revision());
        assertEquals(2, state.groupCount(1));
        assertEquals(1, state.capacityRevision(1));
        assertEquals(List.of(1), notifications);
        state.remove(compound);
        assertEquals(4, state.revision());
        assertEquals(2, state.capacityRevision(1));
        assertEquals(List.of(1, 1), notifications);
    }

    @Test
    void relocationCapacityNotificationsSeeTheCompleteFinalState() throws Exception {
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 3), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2),
                resource(4, 1, 2), resource(5, 2, 2)));
        final var movable = new Request(3, 5);
        final var incoming = new Request(4);
        final var notifications = new ArrayList<Integer>();
        final var moves = List.of(new Engine.Relocation(movable, new Assignment[]{plan(3, 2), plan(5, 2)}));
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) throws Exception {
                if (request == movable) {
                    context.place(request, plan(3, 1), plan(5, 2));
                } else {
                    assertFalse(state.relocateAndPlace(request, new Assignment[]{plan(4, 2)}, moves));
                    assertEquals(1, context.revision());
                    assertEquals(1, context.placement(3).host());
                    assertEquals(2, context.placement(5).host());
                    assertTrue(notifications.isEmpty());
                    assertTrue(state.relocateAndPlace(request, new Assignment[]{plan(4, 1)}, moves));
                }
            }

            @Override
            public void capacityChanged(int host) {
                if (context.revision() == 1) {
                    assertEquals(1, context.placement(3).host());
                    assertEquals(2, context.placement(5).host());
                    assertNull(context.placement(4));
                } else {
                    assertEquals(4, context.revision());
                    assertEquals(2, context.placement(3).host());
                    assertEquals(2, context.placement(5).host());
                    assertEquals(1, context.placement(4).host());
                    assertEquals(0, context.available(1, 1));
                    assertEquals(1, context.available(2, 1));
                    assertEquals(3, context.capacityRevision(1));
                    assertEquals(3, context.capacityRevision(2));
                }
                assertThrows(IllegalStateException.class, () -> state.remove(movable));
                notifications.add(host);
            }
        };
        state.schedule(algorithm, movable);
        assertEquals(List.of(1, 2), notifications.stream().sorted().toList());
        notifications.clear();
        state.schedule(algorithm, incoming);
        assertEquals(List.of(1, 1, 2, 2), notifications.stream().sorted().toList());
    }

    @Test
    void appliesHardLimitsToTheWholeUnitAndKeepsSoftLimitsFeasible() throws Exception {
        final var state = new Engine(model(
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
        final var unit = new Request(5, 6);
        final var missingDomain = new Request(7);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
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
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setRelation(Relation.COLOCATE).setTarget(3).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(1).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(1))),
                resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 2), resource(5, 2, 3)));
        final var first = new Request(3);
        final var second = new Request(4);
        final var dependent = new Request(5);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
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
        final var state = new Engine(model(
                alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1).setPinnedHost(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2).setCandidates(2)
                        .addLinks(Link.newBuilder().setTarget(3).setDomain(1))),
                candidates(2, 1), resource(1, 1, 1), resource(2, 1, 1), resource(3, 2, 2), resource(4, 2, 3)));
        final var unit = new Request(4, 3);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
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
        final var first = new Request(2);
        final var second = new Request(3);
        final var last = new Request(4);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                throw new AssertionError("Batch override must be used");
            }

            @Override
            public void schedule(Request[] requests) {
                assertThrows(IllegalArgumentException.class,
                        () -> context.canPlace(new Request(2), plan(2, 1)));
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
        final var first = new Request(2);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                assertThrows(IllegalArgumentException.class, () -> context.place(request, plan(2, 1)));
            }

            @Override
            public void capacityChanged(int host) {
                throw new IllegalArgumentException("Broken cache notification");
            }
        };
        final var failure = assertThrows(IllegalStateException.class, () -> state.schedule(algorithm, first));
        assertEquals("Broken cache notification", failure.getCause().getMessage());
        assertNotNull(state.placement(2));
        assertThrows(IllegalStateException.class, () -> state.remove(first));
        assertThrows(IllegalStateException.class, () -> state.schedule(algorithm, new Request(3)));
    }

    @Test
    void restoresSparseDomainCountersAfterGrowthAndUnorderedRemoval() throws Exception {
        final var records = new ArrayList<Record>(List.of(alloc(1, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1))));
        final var requests = new Request[32];
        for (int host = 1; host <= requests.length; host++) {
            records.add(resource(host, 1, 1));
            records.add(resource(host + requests.length, 1, 2));
            requests[host - 1] = new Request(host + requests.length);
        }
        final var state = new Engine(model(records.toArray(Record[]::new)));
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
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
        final var state = new Engine(model(alloc(1, new int[]{1}, 8),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD).setGroup(1)),
                resource(1, 1, 1), resource(2, 0, 2)));
        final var request = new Request(2);
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
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
        final var state = new Engine(model(records.toArray(Record[]::new)));
        final var algorithm = new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                final var plans = new Assignment[request.size()];
                for (int member = 0; member < plans.length; member++) {
                    plans[member] = plan(request.resource(member), 1);
                }
                context.place(request, plans);
            }
        };
        final var joint = new Request(3, 4, 2);
        state.schedule(algorithm, joint);
        state.remove(joint);
        final var owner = new Request(2);
        final var consumer = new Request(3);
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
            final var state = new Engine(model(alloc(1, new int[]{1}, 8),
                    rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)
                            .addDomains(Domain.newBuilder().setLevel(3).setValue(7))),
                    rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1)),
                    rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                            .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(level))),
                    resource(1, 1, 1), resource(2, 1, 1), resource(3, 0, 2), resource(4, 0, 2), resource(5, 0, 3)));
            final var first = new Request(3);
            final var second = new Request(4);
            final var consumer = new Request(5);
            final var algorithm = new SchedulingAlgorithm(state) {
                @Override
                public void schedule(Request request) {
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

    @Test
    void isolatedTrialsRestoreFloatingStorageGpuAndGroupStateExactly() throws Exception {
        final var specification = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 9, 10, 11}, 1, 100, 100, 1),
                alloc(2, new int[]{18, 19, 20}, 20, .1, Double.MAX_VALUE),
                alloc(3, new int[]{1, 11}, .3, .2), alloc(4, new int[]{1, 11}, .1, .2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)
                        .setAlternativeMedia(Media.SSD).setAlternativeSpindles(4)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(1)),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.POD).setGroup(1)
                        .addLinks(Link.newBuilder().setTarget(5).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(2).setDomain(1))),
                rules(Constraints.newBuilder().setId(5).setKind(Kind.SHARD).setGroup(2)
                        .addDisks(DiskPlan.newBuilder().setMedia(Media.SSD).setExtents(1).setSpindles(.1)
                                .setMinDevices(1).setMaxDevices(1).setMinStripes(1).setMaxStripes(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 3, 3), resource(4, 4, 4), resource(5, 0, 5));
        final var source = new Engine(specification);
        final var base = new Request(3);
        final var pending = new Request(4, 5);
        final var notifications = new ArrayList<Integer>();
        final var sourcePlanner = new GreedyPlanner(source);
        final var sourcePolicy = new SchedulingAlgorithm(source) {
            @Override
            public void schedule(Request request) {
                final var plans = sourcePlanner.plan(request, 1, 0);
                assertNotNull(plans);
                context.place(request, plans);
            }

            @Override
            public void capacityChanged(int host) {
                notifications.add(host);
            }
        };
        source.schedule(sourcePolicy, base);
        final var trial = source.trial(specification);
        final var state = trial.state();
        final int gpu = specification.gpus.first(1, 11);
        assertThrows(IllegalStateException.class, () -> state.remove(base));
        final long cpuBits = Double.doubleToLongBits(source.available(1, 1));
        final long spindleBits = Double.doubleToLongBits(source.available(1, 8));
        for (int pass = 0; pass < 20; pass++) {
            if (pass == 0) {
                final var planner = new GreedyPlanner(state);
                final var failing = new SchedulingAlgorithm(state) {
                    @Override
                    public void schedule(Request request) {
                        final var plans = planner.plan(request, 1, 0);
                        assertNotNull(plans);
                        context.place(request, plans);
                    }

                    @Override
                    public void capacityChanged(int host) {
                        throw new IllegalStateException("Private trial callback");
                    }
                };
                assertThrows(IllegalStateException.class, () -> state.schedule(failing, pending));
            } else {
                state.schedule(greedy(state), pending);
                assertNotNull(state.placement(4));
                assertNotNull(state.placement(5));
                assertEquals(2, state.groupCount(1));
                assertEquals(1, state.groupCount(2));
                assertEquals(12, state.gpuAvailable(gpu));
                if ((pass & 1) == 0) {
                    state.remove(pending);
                }
            }
            trial.reset();
            trial.reset();
            assertEquals(cpuBits, Double.doubleToLongBits(state.available(1, 1)));
            assertEquals(spindleBits, Double.doubleToLongBits(state.available(1, 8)));
            assertEquals(source.available(1, 7), state.available(1, 7));
            assertEquals(source.available(1, 9), state.available(1, 9));
            assertEquals(source.available(2, 18), state.available(2, 18));
            assertEquals(source.available(2, 19), state.available(2, 19));
            assertEquals(source.available(2, 19, Media.SSD_VALUE), state.available(2, 19, Media.SSD_VALUE));
            assertEquals(16, state.gpuAvailable(gpu));
            assertEquals(1, state.groupCount(1));
            assertEquals(0, state.groupCount(2));
            assertEquals(1, state.domainCount(1, 1, 1));
            assertEquals(source.revision(), state.revision());
            assertEquals(source.capacityRevision(1), state.capacityRevision(1));
            assertNull(state.placement(4));
            assertNull(state.placement(5));
            assertEquals(List.of(1), notifications);
            assertEquals(cpuBits, Double.doubleToLongBits(source.available(1, 1)));
            assertEquals(16, source.gpuAvailable(gpu));
        }
        source.remove(base);
        assertThrows(IllegalStateException.class, trial::reset);
        assertThrows(IllegalStateException.class, trial::state);
    }

    @Test
    void trialsCopyExternalReadinessAndDoNotLeakIncomingDependencies() throws Exception {
        final var source = new Engine(model(alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.SHARD).setGroup(1)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(2)
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(1).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.SHARED_GROUP).setTarget(1).setDomain(1))),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 3)));
        final var first = new Request(2);
        final var second = new Request(3);
        final var consumer = new Request(4);
        final var policy = greedy(source);
        source.schedule(policy, first, second);
        final var trial = source.trial(source.model());
        final var state = trial.state();
        for (int pass = 0; pass < 2; pass++) {
            state.schedule(greedy(state), consumer);
            assertNotNull(state.placement(4));
            assertEquals(2, state.groupCount(1));
            assertEquals(1, state.groupCount(2));
            assertNull(source.placement(4));
            trial.reset();
            assertEquals(0, state.groupCount(2));
        }
        source.remove(first);
        source.remove(second);
        assertEquals(8, source.available(1, 1));
    }

    @Test
    void virtualBanksPreserveOriginalGroupsAndCloneCompletePhysicalGeometry() throws Exception {
        final var source = model(ClusterSpec.GpuMode.DEVICES_20,
                alloc(1, new int[]{1, 11}, 4, 1), alloc(2, new int[]{18, 19, 20}, 20, 2, 100),
                alloc(3, new int[]{1, 11}, .5, .2),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.DISK).setParent(1).setMedia(Media.HDD)),
                rules(Constraints.newBuilder().setId(3).setKind(Kind.POD).setGroup(1).setPinnedHost(1).setCandidates(2)
                        .addLinks(Link.newBuilder().setTarget(5).setDomain(1))
                        .addLinks(Link.newBuilder().setRelation(Relation.READY_GROUP).setTarget(2).setDomain(1))
                        .addLimits(Limit.newBuilder().setDomain(1).setMax(1).setHard(true))),
                rules(Constraints.newBuilder().setId(4).setKind(Kind.SHARD).setGroup(2)
                        .addDisks(DiskPlan.newBuilder().setMedia(Media.HDD).setLayout(Layout.RAID1)
                                .setExtents(9).setMetadataExtents(1).setMinDevices(2).setMaxDevices(2)
                                .setMinStripes(1).setMaxStripes(2))),
                candidates(2, 1), resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2),
                resource(4, 3, 3), resource(5, 0, 4));
        final var virtual = source.withVirtualResources(new ClusterSpec.VirtualResource[]{
                new ClusterSpec.VirtualResource(4, 3, new int[]{7, 4}),
                new ClusterSpec.VirtualResource(5, 4, new int[0])});
        assertTrue(virtual.extendsModel(source));
        assertTrue(virtual.allocations == source.allocations);
        assertTrue(virtual.candidates == source.candidates);
        assertArrayEquals(source.alloc, java.util.Arrays.copyOf(virtual.alloc, source.alloc.length));
        assertArrayEquals(source.constraints, java.util.Arrays.copyOf(virtual.constraints, source.constraints.length));
        assertEquals(source.gpus.first(1, 11), virtual.gpus.first(1, 11));
        final var canonical = new Engine(source);
        final var trial = canonical.trial(virtual);
        final var state = trial.state();
        final var unit = new Request(6, 7);
        final var policy = greedy(state);
        state.schedule(policy, unit);
        assertNotNull(state.placement(6));
        assertNotNull(state.placement(7));
        assertEquals(10, state.available(2, 18));
        assertEquals(10, state.available(3, 18));
        assertEquals(1, state.groupCount(3));
        assertEquals(1, state.groupCount(4));
        assertEquals(0, state.groupCount(1));
        assertEquals(0, state.groupCount(2));
        state.remove(unit);
        assertEquals(4, state.available(1, 1));
        assertEquals(20, state.available(2, 18));
        assertEquals(20, state.available(3, 18));
        assertEquals(20, state.gpuAvailable(virtual.gpus.first(1, 11)));
        assertEquals(0, state.groupCount(3));
        assertEquals(0, state.groupCount(4));
        state.schedule(policy, unit);
        assertNotNull(state.placement(6));
        assertNotNull(state.placement(7));
        trial.reset();
        assertEquals(20, state.available(2, 18));
        assertEquals(20, state.gpuAvailable(virtual.gpus.first(1, 11)));
        canonical.schedule(greedy(canonical), new Request(4, 5));
        assertNotNull(canonical.placement(4));
        assertEquals(1, canonical.groupCount(1));
        assertEquals(1, canonical.groupCount(2));
        assertThrows(IllegalArgumentException.class, () -> source.withVirtualResources(new ClusterSpec.VirtualResource[]{
                new ClusterSpec.VirtualResource(1, 0, new int[0])}));
        assertThrows(IllegalArgumentException.class, () -> source.withVirtualResources(new ClusterSpec.VirtualResource[]{
                new ClusterSpec.VirtualResource(4, 1, new int[]{5, 2})}));
        assertThrows(IllegalArgumentException.class, () -> source.withVirtualResources(new ClusterSpec.VirtualResource[]{
                new ClusterSpec.VirtualResource(4, 3, new int[0])}));
        assertThrows(IllegalArgumentException.class, () -> canonical.trial(model(
                alloc(1, new int[]{1}, 1), rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                resource(1, 1, 1))));
    }

    @Test
    void trialsRejectNestedSnapshotsAndInvalidateWhenTheirSourceFailsWithoutACommit() throws Exception {
        final var source = scalarState();
        final var detached = source.trial(source.model()).state();
        assertThrows(IllegalStateException.class, () -> detached.trial(detached.model()));
        final var escaped = new Engine.Trial[1];
        final var failing = new SchedulingAlgorithm(source) {
            @Override
            public void schedule(Request request) {
                escaped[0] = context.trial(context.model());
                throw new IllegalStateException("Canonical policy failed");
            }
        };
        assertThrows(IllegalStateException.class, () -> source.schedule(failing, new Request(2)));
        assertEquals(0, source.revision());
        assertThrows(IllegalStateException.class, escaped[0]::state);
        assertThrows(IllegalStateException.class, escaped[0]::reset);
        assertThrows(IllegalStateException.class, () -> detached.available(1, 1));
    }

    private static Engine scalarState() throws Exception {
        return new Engine(model(alloc(1, new int[]{1}, 8), alloc(2, new int[]{1}, 1),
                rules(Constraints.newBuilder().setId(1).setKind(Kind.HOST)),
                rules(Constraints.newBuilder().setId(2).setKind(Kind.POD)),
                resource(1, 1, 1), resource(2, 2, 2), resource(3, 2, 2), resource(4, 2, 2)));
    }

    private static SchedulingAlgorithm greedy(Engine state) {
        final var planner = new GreedyPlanner(state);
        return new SchedulingAlgorithm(state) {
            @Override
            public void schedule(Request request) {
                final var assignment = planner.plan(request, 1, 0);
                if (assignment != null) {
                    context.place(request, assignment);
                }
            }
        };
    }

    private static Assignment gpuPlan(int resource, int host, int... devices) {
        return new Assignment(resource, host, 0, new int[]{0}, new int[0], devices);
    }

    private static Assignment plan(int resource, int host) {
        return plan(resource, host, 0);
    }

    private static Assignment plan(int resource, int host, int numa) {
        return new Assignment(resource, host, numa, new int[]{0}, new int[0]);
    }
}
