package one.cloud.schedulers;

import java.util.Arrays;

public final class FitCountScheduler extends PlacementAlgorithm {
    private final PlacementPlanner planner;
    private final FitCountCache cache;
    private final int cap;
    private final int[] hosts;
    private final Placement[][] plans;

    public FitCountScheduler(PlacementContext context, long seed, PlacementRequest[] catalogue) {
        this(context, seed, catalogue, 10, 256);
    }

    public FitCountScheduler(PlacementContext context, long seed, PlacementRequest[] catalogue,
            int maxFitCount, int maxCachedShapes) {
        super(context, seed);
        planner = new PlacementPlanner(context);
        cache = new FitCountCache(context, planner, catalogue, maxFitCount, maxCachedShapes);
        cap = maxFitCount;
        int candidates = 0;
        for (int host = 1; host <= context.model().hostCount; host++) {
            candidates = Math.addExact(candidates, planner.numaNodes(host).length);
        }
        hosts = new int[candidates];
        plans = new Placement[hosts.length][];
    }

    @Override
    public void schedule(PlacementRequest request) {
        int allocated = 0;
        try {
            for (int host = 1; host <= context.model().hostCount; host++) {
                for (final int numa : planner.numaNodes(host)) {
                    final var plan = planner.plan(request, host, numa);
                    if (plan != null) {
                        hosts[allocated] = host;
                        plans[allocated++] = plan;
                    }
                }
            }
            int candidates = allocated;
            if (candidates == 0) {
                return;
            }
            if (candidates > 1) {
                cache.beginOrder();
                for (var sample = cache.next(); sample != null; sample = cache.next()) {
                    int best = Integer.MAX_VALUE;
                    int retained = 0;
                    for (int index = 0; index < candidates; index++) {
                        final int host = hosts[index];
                        final var plan = plans[index];
                        final int loss = cache.count(sample, host) - planner.fitCount(sample.request, host, plan, cap);
                        if (loss < best) {
                            retained = 0;
                            best = loss;
                        }
                        if (loss == best) {
                            hosts[retained] = host;
                            plans[retained++] = plan;
                        }
                    }
                    candidates = retained;
                    if (candidates == 1) {
                        break;
                    }
                }
            }
            context.place(request, plans[candidates == 1 ? 0 : random.nextInt(candidates)]);
        } finally {
            Arrays.fill(plans, 0, allocated, null);
        }
    }

    @Override
    public void placed(Placement placement) {
        cache.changed(placement.host());
    }

    @Override
    public void removed(Placement placement) {
        cache.changed(placement.host());
    }
}
