package one.cloud.benchmark.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import one.cloud.benchmark.algorithms.AlgorithmRegistry;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.engine.Engine;
import one.cloud.benchmark.engine.GreedyPlanner;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.model.Assignment;
import one.cloud.benchmark.model.Request;

public final class SimulatorSession implements AutoCloseable {
    private static final int DIMENSIONS = 20;
    private static final int PROBE_CAP = 10;
    private final ScenarioInput input;
    private final List<List<Integer>> inventory = new ArrayList<>();
    private final double[][] initial;
    private final int[] resourceUnits;
    private String error;
    private List<AlgorithmRegistry.Settings> settings;
    private final List<Lane> lanes = new ArrayList<>();
    private int cursor;
    private boolean failed;
    private boolean closed;

    public SimulatorSession(ScenarioInput input, List<AlgorithmRegistry.Settings> settings) throws Exception {
        this.input = input;
        initial = new double[input.model().alloc.length][];
        resourceUnits = new int[initial.length];
        Arrays.fill(resourceUnits, -1);
        for (int unit = 0; unit < input.requests().length; unit++) {
            final var request = input.requests()[unit];
            for (int member = 0; member < request.size(); member++) {
                resourceUnits[request.resource(member)] = unit;
            }
        }
        for (int host = 0; host <= input.model().hostCount; host++) {
            inventory.add(new ArrayList<>());
        }
        final var pristine = new Engine(input.model());
        for (int resource = 1; resource < initial.length; resource++) {
            final int kind = kind(resource);
            if (kind >= Kind.HOST_VALUE && kind <= Kind.DISK_VALUE) {
                final int host = kind == Kind.HOST_VALUE ? resource : input.model().rules.parent[profile(resource)];
                inventory.get(host).add(resource);
                initial[resource] = new double[DIMENSIONS + 1];
                for (int dimension = 1; dimension <= DIMENSIONS; dimension++) {
                    initial[resource][dimension] = pristine.available(resource, dimension);
                }
            }
        }
        setAlgorithms(settings);
    }

    public ScenarioInput input() {
        return input;
    }

    public List<AlgorithmRegistry.Settings> settings() {
        return settings;
    }

    public int cursor() {
        return cursor;
    }

    public void step(int count) throws Exception {
        if (count < 0 || count > 10000) {
            throw new IllegalArgumentException("Step count must be between 0 and 10000");
        }
        checkOpen();
        final int end = (int) Math.min((long) input.events().length, (long) cursor + count);
        try {
            while (cursor < end) {
                final int event = input.events()[cursor];
                final int unit = Math.abs(event) - 1;
                final var request = input.requests()[unit];
                for (final var lane : lanes) {
                    lane.process(cursor, unit, request, event > 0);
                }
                cursor++;
            }
        } catch (Exception | Error failure) {
            failed = true;
            error = failure.getMessage();
            try {
                closeLanes();
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    public void reset() throws Exception {
        rebuild(settings, 0);
    }

    public void setAlgorithms(List<AlgorithmRegistry.Settings> settings) throws Exception {
        if (settings == null || settings.isEmpty() || settings.size() > 6) {
            throw new IllegalArgumentException("Choose between one and six algorithms");
        }
        rebuild(List.copyOf(settings), cursor);
    }

    private void rebuild(List<AlgorithmRegistry.Settings> selected, int position) throws Exception {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        selected = selected.stream().map(AlgorithmRegistry::normalize).toList();
        for (final var setting : selected) {
            final var descriptor = AlgorithmRegistry.algorithms().stream()
                    .filter(value -> value.id().equals(setting.id())).findFirst().orElseThrow();
            if (descriptor.requiresPrior()) {
                throw new IllegalArgumentException("Algorithm " + setting.id()
                        + " requires a prior file and is available through the CLI only");
            }
        }
        final var replacements = new ArrayList<Lane>();
        try {
            for (final var setting : selected) {
                replacements.add(new Lane(setting));
            }
            for (int index = 0; index < position; index++) {
                final int event = input.events()[index];
                final int unit = Math.abs(event) - 1;
                for (final var lane : replacements) {
                    lane.process(index, unit, input.requests()[unit], event > 0);
                }
            }
        } catch (Exception | Error failure) {
            try {
                closeLanes(replacements);
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
        closeLanes();
        lanes.addAll(replacements);
        settings = selected;
        cursor = position;
        failed = false;
        error = null;
    }

    public JsonObject state(int hostOffset, int hostLimit, int eventOffset, int eventLimit) {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        final var state = new JsonObject();
        state.addProperty("failed", failed);
        state.addProperty("error", error);
        state.addProperty("source", input.source());
        state.addProperty("digest", input.digest());
        state.addProperty("cursor", cursor);
        state.addProperty("totalEvents", input.events().length);
        state.addProperty("rawOperations", input.rawOperations());
        state.addProperty("partialChurn", input.partialChurn());
        state.addProperty("expandedRestarts", input.expandedRestarts());
        state.addProperty("gpuMode", input.model().gpuMode.name());
        state.addProperty("extentBytes", input.model().extentBytes);
        state.addProperty("syntheticGpuDevices", input.model().gpus != null);
        state.addProperty("normalizationVersion", "dataset".equals(input.source())
                ? one.cloud.benchmark.runner.Scenario.NORMALIZATION_VERSION : "manual");
        final var catalogue = new JsonArray();
        for (final var request : input.catalogue()) {
            final var item = new JsonObject();
            item.addProperty("name", requestName(request));
            final var members = new JsonArray();
            for (int member = 0; member < request.size(); member++) {
                members.add(request.resource(member));
            }
            item.add("resources", members);
            catalogue.add(item);
        }
        state.add("catalogue", catalogue);
        final var laneStates = new JsonArray();
        final int first = Math.max(0, Math.min(hostOffset, input.model().hostCount));
        final int end = Math.min(input.model().hostCount, first + Math.max(0, Math.min(hostLimit, 48)));
        for (final var lane : lanes) {
            final var view = new JsonObject();
            view.add("algorithm", algorithm(lane.settings));
            view.add("counters", lane.counters());
            final var latency = new JsonObject();
            latency.add("create", lane.creates.json());
            latency.add("remove", lane.removes.json());
            latency.addProperty("plannerCalls", lane.algorithm.plannerCalls());
            view.add("latency", latency);
            view.add("resources", aggregateResources(lane));
            view.addProperty("totalHosts", input.model().hostCount);
            view.addProperty("hostOffset", first);
            view.add("lastEvent", lane.lastEvent);
            final var hosts = new JsonArray();
            for (int host = first + 1; host <= end; host++) {
                hosts.add(host(lane, host, false));
            }
            view.add("hosts", hosts);
            laneStates.add(view);
        }
        state.add("lanes", laneStates);
        final int from = Math.max(0, Math.min(eventOffset, input.events().length));
        state.addProperty("eventOffset", from);
        final var events = new JsonArray();
        final int until = Math.min(input.events().length, from + Math.max(0, Math.min(eventLimit, 50)));
        for (int index = from; index < until; index++) {
            events.add(event(index));
        }
        state.add("events", events);
        return state;
    }

    public JsonObject inspect(int lane, int resource) {
        checkOpen();
        if (lane < 0 || lane >= lanes.size() || resource <= 0 || resource >= input.model().alloc.length) {
            throw new IllegalArgumentException("Unknown lane or resource");
        }
        if (kind(resource) == Kind.HOST_VALUE) {
            return host(lanes.get(lane), resource, true);
        }
        if (initial[resource] != null) {
            final var result = identity(resource);
            result.add("resources", inventoryResources(lanes.get(lane), resource));
            result.addProperty("parent", input.model().rules.parent[profile(resource)]);
            result.addProperty("media", input.model().rules.media[profile(resource)]);
            return result;
        }
        return member(lanes.get(lane), resource, true);
    }

    public JsonObject probes() {
        checkOpen();
        final var result = new JsonObject();
        result.addProperty("cursor", cursor);
        final var values = new JsonArray();
        for (final var lane : lanes) {
            final long started = System.nanoTime();
            final var planner = new GreedyPlanner(lane.engine);
            final var fits = new JsonArray();
            for (int index = 0; index < Math.min(input.catalogue().length, 16); index++) {
                final var request = input.catalogue()[index];
                long count = 0;
                for (int host = 1; host <= input.model().hostCount; host++) {
                    count += planner.fitCount(request, host, PROBE_CAP);
                }
                final var fit = new JsonObject();
                fit.addProperty("name", name(request.resource(0)));
                fit.addProperty("count", count);
                fits.add(fit);
            }
            final var value = new JsonObject();
            value.add("algorithm", algorithm(lane.settings));
            value.add("fitCounts", fits);
            value.addProperty("elapsedMs", (System.nanoTime() - started) / 1e6);
            value.addProperty("perHostCap", PROBE_CAP);
            values.add(value);
        }
        result.add("lanes", values);
        return result;
    }

    @Override
    public void close() throws Exception {
        closed = true;
        closeLanes();
    }

    private void closeLanes() throws Exception {
        closeLanes(lanes);
    }

    private static void closeLanes(List<Lane> selected) throws Exception {
        Exception failure = null;
        for (final var lane : selected) {
            try {
                lane.algorithm.close();
            } catch (Exception error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        selected.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private void checkOpen() {
        if (closed || failed) {
            throw new IllegalStateException("Session is closed or failed; reset before continuing");
        }
    }

    private JsonObject event(int index) {
        final int value = input.events()[index];
        final int unit = Math.abs(value) - 1;
        final var event = new JsonObject();
        event.addProperty("index", index);
        event.addProperty("kind", value > 0 ? "create" : "delete");
        event.addProperty("request", unit);
        event.addProperty("name", input.requestNames()[unit]);
        final var members = new JsonArray();
        final var request = input.requests()[unit];
        for (int item = 0; item < request.size(); item++) {
            members.add(member(null, request.resource(item), false));
        }
        event.add("members", members);
        return event;
    }

    private JsonObject host(Lane lane, int host, boolean detailed) {
        final var result = identity(host);
        result.add("resources", inventoryResources(lane, host));
        final var members = new JsonArray();
        int pods = 0, shards = 0;
        for (final var assignment : lane.hostMembers.get(host).values()) {
            pods += kind(assignment.resource()) == Kind.POD_VALUE ? 1 : 0;
            shards += kind(assignment.resource()) == Kind.SHARD_VALUE ? 1 : 0;
            if (detailed || members.size() < 12) {
                members.add(member(lane, assignment.resource(), detailed));
            }
        }
        result.addProperty("podCount", pods);
        result.addProperty("shardCount", shards);
        result.addProperty("activeResources", lane.hostMembers.get(host).size());
        result.add("members", members);
        if (detailed) {
            final var numa = new JsonArray();
            final var disks = new JsonArray();
            for (final int resource : inventory.get(host)) {
                if (resource != host) {
                    final var item = identity(resource);
                    item.add("resources", inventoryResources(lane, resource));
                    if (kind(resource) == Kind.DISK_VALUE) {
                        item.addProperty("media", input.model().rules.media[profile(resource)]);
                        disks.add(item);
                    } else {
                        numa.add(item);
                    }
                }
            }
            result.add("numa", numa);
            result.add("disks", disks);
            final var gpus = new JsonArray();
            if (input.model().gpus != null) {
                for (final int type : new int[]{11, 17}) {
                    for (int device = input.model().gpus.first(host, type);
                            device < input.model().gpus.end(host, type); device++) {
                        final var gpu = new JsonObject();
                        gpu.addProperty("id", device);
                        gpu.addProperty("type", resourceName(type));
                        gpu.addProperty("totalShares", 20);
                        gpu.addProperty("freeShares", lane.engine.gpuAvailable(device));
                        gpus.add(gpu);
                    }
                }
            }
            result.add("gpus", gpus);
        }
        return result;
    }

    private JsonObject member(Lane lane, int resource, boolean detailed) {
        final var result = identity(resource);
        if (resourceUnits[resource] >= 0) {
            result.addProperty("request", resourceUnits[resource]);
            result.addProperty("requestName", input.requestNames()[resourceUnits[resource]]);
        }
        final var demands = new JsonArray();
        final var model = input.model();
        final int allocation = model.alloc[resource];
        for (int index = model.allocations.offset[allocation]; index < model.allocations.offset[allocation + 1]; index++) {
            final int dimension = model.allocations.type[index];
            final var demand = new JsonObject();
            demand.addProperty("id", dimension);
            demand.addProperty("name", resourceName(dimension));
            demand.addProperty("amount", model.allocations.amount[index]);
            demand.addProperty("unit", unit(dimension));
            demands.add(demand);
        }
        result.add("resources", demands);
        if (lane != null) {
            final var assignment = lane.engine.placement(resource);
            if (assignment != null) {
                final var placement = new JsonObject();
                placement.addProperty("host", assignment.host());
                placement.addProperty("numa", assignment.numa());
                placement.add("devices", integers(assignment.devices()));
                placement.add("diskOffsets", integers(assignment.diskOffsets()));
                placement.add("gpus", integers(assignment.gpus()));
                result.add("assignment", placement);
            }
        }
        if (detailed) {
            final int unit = resourceUnits[resource];
            if (unit >= 0) {
                final var siblings = new JsonArray();
                final var request = input.requests()[unit];
                for (int index = 0; index < request.size(); index++) {
                    siblings.add(identity(request.resource(index)));
                }
                result.add("unitMembers", siblings);
            }
            final int profile = profile(resource);
            final var rules = new JsonObject();
            rules.addProperty("pinnedHost", model.rules.pinnedHost[profile]);
            rules.addProperty("candidateSet", model.rules.candidates[profile]);
            rules.addProperty("group", model.rules.group[profile]);
            rules.addProperty("flags", model.rules.flags[profile]);
            final var allowedHosts = new JsonArray();
            for (int host = 1; host <= model.hostCount; host++) {
                if (model.candidates.allows(model.rules.candidates[profile], host)) {
                    allowedHosts.add(host);
                }
            }
            rules.add("allowedHosts", allowedHosts);
            final var limits = new JsonArray();
            for (int index = model.rules.limitOffset[profile]; index < model.rules.limitOffset[profile + 1]; index++) {
                final var limit = new JsonObject();
                limit.addProperty("domain", model.rules.limitDomain[index]);
                limit.addProperty("max", model.rules.limitMax[index]);
                limit.addProperty("hard", model.rules.limitHard[index]);
                limits.add(limit);
            }
            rules.add("limits", limits);
            final var plans = new JsonArray();
            for (int index = model.rules.diskOffset[profile]; index < model.rules.diskOffset[profile + 1]; index++) {
                final var plan = new JsonObject();
                plan.addProperty("media", model.rules.diskMedia[index]);
                plan.addProperty("layout", model.rules.diskLayout[index]);
                plan.addProperty("extents", model.rules.diskExtents[index]);
                plan.addProperty("spindles", model.rules.diskSpindles[index]);
                if (Double.isFinite(model.rules.diskIops[index])) {
                    plan.addProperty("iops", model.rules.diskIops[index]);
                }
                plan.addProperty("minDevices", model.rules.minDevices[index]);
                plan.addProperty("maxDevices", model.rules.maxDevices[index]);
                plan.addProperty("minStripes", model.rules.minStripes[index]);
                plan.addProperty("maxStripes", model.rules.maxStripes[index]);
                plan.addProperty("flags", model.rules.diskFlags[index]);
                plans.add(plan);
            }
            rules.add("diskPlans", plans);
            result.add("rules", rules);
            final var links = new JsonArray();
            for (int index = model.rules.linkOffset[profile]; index < model.rules.linkOffset[profile + 1]; index++) {
                final var link = new JsonObject();
                link.addProperty("target", model.rules.linkTarget[index]);
                link.addProperty("kind", model.rules.linkKind[index]);
                link.addProperty("domain", model.rules.linkDomain[index]);
                links.add(link);
            }
            result.add("links", links);
        }
        return result;
    }

    private JsonArray aggregateResources(Lane lane) {
        final var result = new JsonArray();
        for (int dimension = 1; dimension <= DIMENSIONS; dimension++) {
            double total = 0, free = 0;
            for (int host = 1; host <= input.model().hostCount; host++) {
                if (dimension < 18) {
                    total += initial[host][dimension];
                    free += lane.engine.available(host, dimension);
                } else {
                    for (final int resource : inventory.get(host)) {
                        if (kind(resource) == Kind.DISK_VALUE) {
                            total += initial[resource][dimension];
                            free += lane.engine.available(resource, dimension);
                        }
                    }
                }
            }
            result.add(resource(dimension, total, free));
        }
        return result;
    }

    private JsonArray inventoryResources(Lane lane, int resource) {
        final var result = new JsonArray();
        for (int dimension = 1; dimension <= DIMENSIONS; dimension++) {
            double total = initial[resource][dimension];
            double free = lane.engine.available(resource, dimension);
            if (kind(resource) == Kind.HOST_VALUE && dimension >= 18) {
                total = 0;
                free = 0;
                for (final int child : inventory.get(resource)) {
                    if (kind(child) == Kind.DISK_VALUE) {
                        total += initial[child][dimension];
                        free += lane.engine.available(child, dimension);
                    }
                }
            }
            result.add(resource(dimension, total, free));
        }
        return result;
    }

    private JsonObject resource(int dimension, double total, double free) {
        final var value = new JsonObject();
        value.addProperty("id", dimension);
        value.addProperty("name", resourceName(dimension));
        value.addProperty("unit", unit(dimension));
        final double used = total - free;
        final boolean known = Double.isFinite(total) && Double.isFinite(free) && Double.isFinite(used)
                && total != Double.MAX_VALUE && free != Double.MAX_VALUE;
        value.addProperty("capacityKnown", known);
        if (known) {
            value.addProperty("total", total);
            value.addProperty("free", free);
            value.addProperty("used", used);
        } else {
            value.add("total", com.google.gson.JsonNull.INSTANCE);
            value.add("free", com.google.gson.JsonNull.INSTANCE);
            value.add("used", com.google.gson.JsonNull.INSTANCE);
            value.addProperty("reason", "unbounded-in-model");
        }
        return value;
    }

    private JsonObject identity(int resource) {
        final var result = new JsonObject();
        result.addProperty("id", resource);
        result.addProperty("name", name(resource));
        final var kind = Kind.forNumber(kind(resource));
        result.addProperty("kind", kind == null ? "UNKNOWN" : kind.name());
        return result;
    }

    private String requestName(Request request) {
        final int unit = resourceUnits[request.resource(0)];
        return unit < 0 ? name(request.resource(0)) : input.requestNames()[unit];
    }

    private String name(int resource) {
        return input.names().getOrDefault(resource, "resource-" + resource);
    }

    private int profile(int resource) {
        return input.model().constraints[resource];
    }

    private int kind(int resource) {
        return input.model().rules.kind[profile(resource)];
    }

    private boolean gpuPod(int resource) {
        if (kind(resource) != Kind.POD_VALUE) {
            return false;
        }
        final int allocation = input.model().alloc[resource];
        for (int index = input.model().allocations.offset[allocation];
                index < input.model().allocations.offset[allocation + 1]; index++) {
            final int type = input.model().allocations.type[index];
            if ((type == 11 || type == 17) && input.model().allocations.amount[index] > 0) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject algorithm(AlgorithmRegistry.Settings settings) {
        final var result = new JsonObject();
        result.addProperty("id", settings.id());
        final var parameters = new JsonObject();
        settings.parameters().forEach(parameters::add);
        result.add("parameters", parameters);
        return result;
    }

    private static JsonArray integers(int[] values) {
        final var result = new JsonArray();
        for (final int value : values) {
            result.add(value);
        }
        return result;
    }

    private static String resourceName(int dimension) {
        final var type = ResourceType.forNumber(dimension);
        return type == null ? "RESOURCE_" + dimension : type.name();
    }

    private static String unit(int dimension) {
        return switch (dimension) {
            case 2 -> "MiB";
            case 3, 4, 13, 14 -> "Mbit/s";
            case 5, 7, 9 -> "GiB";
            case 18 -> "extents";
            case 20 -> "IOPS";
            default -> "count";
        };
    }

    private final class Lane {
        private final AlgorithmRegistry.Settings settings;
        private final Engine engine = new Engine(input.model());
        private final SchedulingAlgorithm algorithm;
        private final boolean[] admitted = new boolean[input.requests().length];
        private final boolean[] desired = new boolean[input.requests().length];
        private final List<LinkedHashMap<Integer, Assignment>> hostMembers = new ArrayList<>();
        private final Timings creates = new Timings(input.events().length);
        private final Timings removes = new Timings(input.events().length);
        private final long[][] kinds = new long[4][3];
        private long attempted, placed, removed, skipped, active;
        private JsonObject lastEvent = new JsonObject();

        private Lane(AlgorithmRegistry.Settings settings) throws Exception {
            this.settings = settings;
            for (int host = 0; host <= input.model().hostCount; host++) {
                hostMembers.add(new LinkedHashMap<>());
            }
            algorithm = AlgorithmRegistry.create(settings, engine, input.catalogue().clone());
        }

        private void process(int eventIndex, int unit, Request request, boolean create) throws Exception {
            final long start;
            final long elapsed;
            String status;
            if (create) {
                if (desired[unit]) {
                    throw new IllegalArgumentException("Request is already active");
                }
                desired[unit] = true;
                attempted++;
                count(request, 0, 1);
                start = System.nanoTime();
                engine.schedule(algorithm, request);
                elapsed = System.nanoTime() - start;
                creates.add(elapsed);
                admitted[unit] = engine.placement(request.resource(0)) != null;
                status = admitted[unit] ? "placed" : "rejected";
                if (admitted[unit]) {
                    placed++;
                    active++;
                    count(request, 1, 1);
                    count(request, 2, 1);
                    for (int member = 0; member < request.size(); member++) {
                        final var assignment = engine.placement(request.resource(member));
                        hostMembers.get(assignment.host()).put(assignment.resource(), assignment);
                    }
                }
            } else if (!desired[unit]) {
                throw new IllegalArgumentException("Request is not logically active");
            } else if (admitted[unit]) {
                desired[unit] = false;
                final int[] hosts = new int[request.size()];
                for (int member = 0; member < request.size(); member++) {
                    hosts[member] = engine.placement(request.resource(member)).host();
                }
                start = System.nanoTime();
                engine.remove(request);
                elapsed = System.nanoTime() - start;
                removes.add(elapsed);
                admitted[unit] = false;
                active--;
                removed++;
                count(request, 2, -1);
                count(request, 3, 1);
                for (int member = 0; member < request.size(); member++) {
                    hostMembers.get(hosts[member]).remove(request.resource(member));
                }
                status = "removed";
            } else {
                desired[unit] = false;
                elapsed = 0;
                skipped++;
                status = "skipped";
            }
            lastEvent = event(eventIndex);
            lastEvent.addProperty("status", status);
            lastEvent.addProperty("durationMs", elapsed / 1e6);
            final var members = new JsonArray();
            for (int index = 0; index < request.size(); index++) {
                members.add(member(this, request.resource(index), false));
            }
            lastEvent.add("members", members);
        }

        private void count(Request request, int category, int delta) {
            for (int index = 0; index < request.size(); index++) {
                final int resource = request.resource(index);
                final int type = kind(resource);
                if (type == Kind.POD_VALUE) {
                    kinds[category][0] += delta;
                } else if (type == Kind.SHARD_VALUE) {
                    kinds[category][1] += delta;
                }
                if (gpuPod(resource)) {
                    kinds[category][2] += delta;
                }
            }
        }

        private JsonObject counters() {
            final var result = new JsonObject();
            result.addProperty("attemptedUnits", attempted);
            result.addProperty("placedUnits", placed);
            result.addProperty("rejectedUnits", attempted - placed);
            result.addProperty("removedUnits", removed);
            result.addProperty("skippedRemovals", skipped);
            result.addProperty("activeUnits", active);
            int usedHosts = 0;
            for (final var members : hostMembers) {
                usedHosts += members.isEmpty() ? 0 : 1;
            }
            result.addProperty("usedHosts", usedHosts);
            final String[] categories = {"attempted", "placed", "active", "removed"};
            final String[] types = {"Pods", "Shards", "GpuPods"};
            for (int category = 0; category < categories.length; category++) {
                for (int type = 0; type < types.length; type++) {
                    result.addProperty(categories[category] + types[type], kinds[category][type]);
                }
            }
            return result;
        }
    }

    private static final class Timings {
        private long[] values;
        private int count;
        private long sum;
        private JsonObject cached;

        private Timings(int size) {
            values = new long[Math.min(size, 32)];
        }

        private void add(long nanos) {
            if (count == values.length) {
                values = Arrays.copyOf(values, Math.max(count + 1, values.length * 2));
            }
            values[count++] = nanos;
            sum += nanos;
            cached = null;
        }

        private JsonObject json() {
            if (cached != null) {
                return cached.deepCopy();
            }
            final var result = new JsonObject();
            result.addProperty("count", count);
            result.addProperty("meanMs", count == 0 ? 0 : sum / (double) count / 1e6);
            final var ordered = Arrays.copyOf(values, count);
            Arrays.sort(ordered);
            result.addProperty("p50Ms", percentile(ordered, 0.5));
            result.addProperty("p95Ms", percentile(ordered, 0.95));
            result.addProperty("p99Ms", percentile(ordered, 0.99));
            result.addProperty("lastMs", count == 0 ? 0 : values[count - 1] / 1e6);
            cached = result;
            return result.deepCopy();
        }

        private static double percentile(long[] ordered, double fraction) {
            return ordered.length == 0 ? 0 : ordered[(int) Math.ceil(ordered.length * fraction) - 1] / 1e6;
        }
    }
}
