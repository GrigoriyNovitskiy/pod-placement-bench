package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SmtSolverTest {
    @BeforeAll
    static void requireZ3() {
        try {
            assertTrue(SmtSolver.version().startsWith("Z3 version "));
        } catch (SmtSolver.Unavailable unavailable) {
            assumeTrue(false, unavailable.getMessage());
        }
    }

    @Test
    void maximizesAdmissionAndHonorsSharedCapacity() {
        try (final var solver = new SmtSolver(new int[] {2, 1},
                List.of(new SmtSolver.Row(new long[] {1, 0, 1}, 1)), 1_000_000)) {
            assertArrayEquals(new int[] {1, 0}, solver.next(0));
            assertNull(solver.next(2));
        }
    }

    @Test
    void blocksCompleteDecisionsIncludingSkippedUnits() {
        try (final var solver = new SmtSolver(new int[] {1, 1}, List.of(), 1_000_000)) {
            final var decisions = new HashSet<String>();
            int previous = 2;
            for (int index = 0; index < 4; index++) {
                final int[] choice = solver.next(0);
                final int admitted = (int) Arrays.stream(choice).filter(plan -> plan >= 0).count();
                assertTrue(admitted <= previous);
                previous = admitted;
                assertTrue(decisions.add(Arrays.toString(choice)));
            }
            assertEquals(4, decisions.size());
            assertEquals(0, previous);
            assertNull(solver.next(0));
        }
    }

    @Test
    void representsAUnitWithNoPlansAndReportsResourceExhaustion() {
        try (final var solver = new SmtSolver(new int[] {0}, List.of(), 1_000_000)) {
            assertArrayEquals(new int[] {-1}, solver.next(0));
            assertNull(solver.next(0));
        }
        try (final var solver = new SmtSolver(new int[] {1}, List.of(), 1)) {
            assertThrows(SmtSolver.Limit.class, () -> solver.next(0));
        }
    }
}
