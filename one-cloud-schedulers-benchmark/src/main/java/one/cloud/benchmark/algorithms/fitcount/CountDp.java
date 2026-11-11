package one.cloud.benchmark.algorithms.fitcount;

import java.util.Arrays;

final class CountDp {
    interface Options {
        int load(int host) throws Exception;

        long cost(int copies, int component);
    }

    static final class Limit extends RuntimeException {
        Limit(String reason) {
            super(reason, null, false, false);
        }
    }

    static final class Work {
        private final long limit, memoryLimit;
        long operations, transitions, workspace;

        Work(long limit, long memoryLimit) {
            if (limit <= 0 || memoryLimit <= 0) {
                throw new IllegalArgumentException("Work and workspace limits must be positive");
            }
            this.limit = limit;
            this.memoryLimit = memoryLimit;
        }

        void charge(long count) {
            if (count < 0 || count > limit - operations) {
                throw new Limit("work_budget");
            }
            operations += count;
        }

        void reserve(long bytes) {
            if (bytes < 0 || bytes > memoryLimit - workspace) {
                throw new Limit("workspace_budget");
            }
            workspace += bytes;
        }
    }

    private final BulkModel tree;
    private final int copies, components, states;
    private final Options options;
    private final Work work;
    private final long[][][] costs;
    private final boolean[][][] reachable;
    private final byte[] choices;
    private final int[] resultRows;

    CountDp(BulkModel tree, int copies, int components, Options options, Work work) {
        this.tree = tree;
        this.copies = copies;
        this.components = components;
        this.options = options;
        this.work = work;
        states = copies + 1;
        final int levels = tree.depth + 1;
        final long cells = (long) states * components;
        final long traces = (long) tree.nodes.length * states;
        work.reserve(256L + levels * (256L + 2 * (32 + 8 * cells + states)) + traces + 4L * levels);
        work.charge(2L * levels * (cells + states) + traces);
        if (cells > Integer.MAX_VALUE - 8 || traces > Integer.MAX_VALUE - 8) {
            throw new Limit("workspace_budget");
        }
        costs = new long[levels][2][(int) cells];
        reachable = new boolean[levels][2][states];
        choices = new byte[(int) traces];
        resultRows = new int[levels];
    }

    int[] solve() throws Exception {
        fill(tree.root, 0);
        final int row = resultRows[0];
        int count = Math.min(copies, tree.nodes[tree.root].cap);
        while (!reachable[0][row][count]) {
            count--;
        }
        work.reserve(16L + 4L * (tree.hosts + 1));
        final int[] result = new int[tree.hosts + 1];
        recover(tree.root, count, result);
        return result;
    }

    private void fill(int id, int depth) throws Exception {
        final var node = tree.nodes[id];
        work.charge(states + components);
        Arrays.fill(reachable[depth][0], false);
        Arrays.fill(costs[depth][0], 0, components, 0);
        reachable[depth][0][0] = true;
        if (node.host != 0) {
            final int count = Math.min(Math.min(options.load(node.host), copies), node.cap);
            for (int n = 1; n <= count; n++) {
                reachable[depth][0][n] = true;
                for (int component = 0; component < components; component++) {
                    work.charge(1);
                    costs[depth][0][n * components + component] = options.cost(n, component);
                }
            }
            resultRows[depth] = 0;
            return;
        }
        int row = 0;
        for (final int child : node.children) {
            fill(child, depth + 1);
            final int childRow = resultRows[depth + 1];
            final int next = 1 - row;
            work.charge(states);
            Arrays.fill(reachable[depth][next], false);
            for (int previous = 0; previous <= Math.min(copies, node.cap); previous++) {
                if (!reachable[depth][row][previous]) {
                    continue;
                }
                for (int k = 0; k <= Math.min(copies - previous, node.cap - previous); k++) {
                    if (!reachable[depth + 1][childRow][k]) {
                        continue;
                    }
                    work.transitions++;
                    work.charge(1);
                    final int n = previous + k;
                    if (!reachable[depth][next][n] || better(costs[depth][row], previous,
                            costs[depth + 1][childRow], k, costs[depth][next], n)) {
                        for (int component = 0; component < components; component++) {
                            work.charge(2);
                            costs[depth][next][n * components + component] = Math.addExact(
                                    costs[depth][row][previous * components + component],
                                    costs[depth + 1][childRow][k * components + component]);
                        }
                        reachable[depth][next][n] = true;
                        choices[child * states + n] = (byte) k;
                    }
                }
            }
            row = next;
        }
        resultRows[depth] = row;
    }

    private boolean better(long[] left, int a, long[] right, int b, long[] best, int n) {
        for (int component = 0; component < components; component++) {
            work.charge(2);
            final long value = Math.addExact(left[a * components + component], right[b * components + component]);
            final int comparison = Long.compare(value, best[n * components + component]);
            if (comparison != 0) {
                return comparison < 0;
            }
        }
        return false;
    }

    private void recover(int id, int count, int[] result) {
        if (count == 0) {
            return;
        }
        final var node = tree.nodes[id];
        if (node.host != 0) {
            result[node.host] = count;
            return;
        }
        for (int index = node.children.length - 1; index >= 0; index--) {
            final int child = node.children[index];
            final int selected = Byte.toUnsignedInt(choices[child * states + count]);
            recover(child, selected, result);
            count -= selected;
        }
    }
}
