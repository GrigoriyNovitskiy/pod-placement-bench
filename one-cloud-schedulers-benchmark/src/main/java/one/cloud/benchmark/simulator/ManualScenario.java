package one.cloud.benchmark.simulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.PlacementDatasetProtos;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Alloc;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Candidates;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Constraints;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Layout;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Link;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Media;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Resource;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.ResourceType;
import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

public final class ManualScenario {
    public record Config(List<Host> hosts, List<Unit> requests, List<Event> events, List<String> catalogue) {
        public Config {
            hosts = list(hosts);
            requests = list(requests);
            events = events == null ? null : List.copyOf(events);
            catalogue = catalogue == null ? null : List.copyOf(catalogue);
        }
    }

    public record Host(String name, Map<String, Double> resources, List<Disk> disks) {
        public Host {
            resources = map(resources);
            disks = list(disks);
        }
    }

    public record Disk(String name, String media, Map<String, Double> resources) {
        public Disk {
            media = fallback(media, "SSD");
            resources = map(resources);
        }
    }

    public record Unit(String name, List<Member> members) {
        public Unit {
            members = list(members);
        }
    }

    public record Member(String name, String kind, Map<String, Double> resources, String pinnedHost,
            List<String> allowedHosts, List<DiskPlan> disks) {
        public Member {
            kind = fallback(kind, "POD");
            resources = map(resources);
            allowedHosts = list(allowedHosts);
            disks = list(disks);
        }
    }

    public record DiskPlan(String media, String layout, long extents, double spindles, Double iops,
            boolean exclusive) {
        public DiskPlan {
            media = fallback(media, "SSD");
            layout = fallback(layout, "LINEAR");
        }
    }

    public record Event(String kind, String request) {
    }

    private ManualScenario() {
    }

    public static ScenarioInput compile(Config config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("A manual scenario is required");
        }
        return new Compiler(config).compile();
    }

    public static Config example() {
        final var hosts = new ArrayList<Host>();
        for (int index = 1; index <= 8; index++) {
            final var capacity = new LinkedHashMap<String, Double>();
            capacity.put("VCORES", index <= 4 ? 16.0 : 32.0);
            capacity.put("MEM_MIB", index <= 4 ? 32768.0 : 65536.0);
            capacity.put("LAN_OUT_MBIT", 10000.0);
            capacity.put("LAN_IN_MBIT", 10000.0);
            if (index == 3 || index == 7) {
                capacity.put("NVIDIA_GPUS", 2.0);
            }
            if (index == 8) {
                capacity.put("INTEL_GPUS", 1.0);
            }
            hosts.add(new Host("host-" + index, capacity, List.of(new Disk("ssd-" + index, "SSD",
                    Map.of("DEVICE_EXTENTS", 8192.0, "DEVICE_SPINDLES", 1.0, "DEVICE_IOPS", 100000.0)))));
        }
        final var requests = new ArrayList<Unit>();
        for (int index = 1; index <= 24; index++) {
            final var demand = new LinkedHashMap<String, Double>();
            demand.put("VCORES", index % 3 == 0 ? 8.0 : 2.0);
            demand.put("MEM_MIB", index % 4 == 0 ? 8192.0 : 2048.0);
            if (index % 5 == 0) {
                demand.put("NVIDIA_GPUS", .5);
            }
            final var members = new ArrayList<Member>();
            members.add(new Member("pod-" + index, "POD", demand, null, null, null));
            if (index % 4 == 0) {
                members.add(new Member("volume-" + index, "SHARD", Map.of(), null, null,
                        List.of(new DiskPlan("SSD", "LINEAR", 512, .05, 1000.0, false))));
            }
            requests.add(new Unit("app-" + index, members));
        }
        final var events = new ArrayList<Event>();
        for (final var request : requests) {
            events.add(new Event("create", request.name));
        }
        for (int index = 1; index <= 6; index++) {
            events.add(new Event("delete", "app-" + index));
        }
        return new Config(hosts, requests, events, null);
    }

    private static final class Compiler {
        private final Config config;
        private final ArrayList<Record> allocations = new ArrayList<>();
        private final ArrayList<Record> constraints = new ArrayList<>();
        private final ArrayList<Record> resources = new ArrayList<>();
        private final ArrayList<Record> candidates = new ArrayList<>();
        private final Map<Integer, String> names = new LinkedHashMap<>();
        private final Map<String, Integer> hostIds = new HashMap<>();
        private final Map<String, Integer> unitIds = new LinkedHashMap<>();
        private final Header.Builder header = Header.newBuilder().setVersion(2).setDatasetId(1)
                .setExtentBytes(64L * 1024 * 1024).setDimensionCount(20);
        private Request[] requests;

        private Compiler(Config config) {
            this.config = config;
            if (config.hosts.size() > 128 || config.requests.size() > 1000
                    || config.events != null && config.events.size() > 10000) {
                throw new IllegalArgumentException("Manual scenarios support up to 128 hosts, 1000 units and 10000 events");
            }
        }

        private ScenarioInput compile() throws IOException {
            hosts();
            disks();
            requests();
            final int[] events = events();
            final Request[] catalogue = catalogue();
            final var path = Files.createTempFile("placement-manual-", ".pb");
            try {
                final var records = records();
                final var hash = hash();
                try (final var output = Files.newOutputStream(path)) {
                    for (final var record : records) {
                        record.writeDelimitedTo(output);
                        hash.update(record.toByteArray());
                    }
                }
                final var model = ClusterSpec.from(Dataset.load(path));
                final var requestNames = unitIds.keySet().toArray(String[]::new);
                for (final int event : events) {
                    hash.update(ByteBuffer.allocate(4).putInt(event).array());
                }
                for (final var request : catalogue) {
                    hash.update(ByteBuffer.allocate(4).putInt(request.resource(0)).array());
                }
                for (final var name : names.values()) {
                    final var bytes = name.getBytes(StandardCharsets.UTF_8);
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    hash.update(bytes);
                }
                return new ScenarioInput(model, requests, events, Map.copyOf(names), requestNames,
                        catalogue, "manual", HexFormat.of().formatHex(hash.digest()), events.length, 0, 0);
            } finally {
                Files.deleteIfExists(path);
            }
        }

        private void hosts() {
            for (final var host : config.hosts) {
                final String name = name(host.name);
                final int id = resources.size() + 1;
                if (hostIds.putIfAbsent(name, id) != null) {
                    throw new IllegalArgumentException("Duplicate host: " + name);
                }
                add(name, host.resources, Constraints.newBuilder().setKind(Kind.HOST));
            }
        }

        private void disks() {
            int count = 0;
            for (final var host : config.hosts) {
                if (host.disks.size() > 32) {
                    throw new IllegalArgumentException("A manual host supports up to 32 disks");
                }
                for (final var disk : host.disks) {
                    count++;
                    add(fallback(disk.name, host.name + "-disk-" + count), disk.resources,
                            Constraints.newBuilder().setKind(Kind.DISK).setParent(hostIds.get(host.name))
                                    .setMedia(media(disk.media)));
                }
            }
        }

        private void requests() {
            requests = new Request[config.requests.size()];
            int unit = 0;
            for (final var request : config.requests) {
                final String name = name(request.name);
                if (unitIds.putIfAbsent(name, unit) != null) {
                    throw new IllegalArgumentException("Duplicate request: " + name);
                }
                if (request.members.isEmpty() || request.members.size() > 16) {
                    throw new IllegalArgumentException("An atomic unit must have 1–16 members");
                }
                final int first = resources.size() + 1;
                final var ids = new int[request.members.size()];
                for (int member = 0; member < ids.length; member++) {
                    final var value = request.members.get(member);
                    final var kind = kind(value.kind);
                    final var rules = Constraints.newBuilder().setKind(kind);
                    if (member > 0) {
                        rules.addLinks(Link.newBuilder().setRelation(Relation.COLOCATE).setDomain(1).setTarget(first));
                        header.setLinkCount(header.getLinkCount() + 1);
                    }
                    if (value.pinnedHost != null && !value.pinnedHost.isBlank()) {
                        rules.setPinnedHost(host(value.pinnedHost));
                    }
                    if (!value.allowedHosts.isEmpty()) {
                        final var selected = value.allowedHosts.stream().mapToInt(this::host).distinct().sorted().toArray();
                        final var set = Candidates.newBuilder().setId(candidates.size() + 2);
                        for (final int host : selected) {
                            set.addHost(host);
                        }
                        candidates.add(Record.newBuilder().setCandidates(set).build());
                        header.setCandidateHostCount(header.getCandidateHostCount() + selected.length);
                        rules.setCandidates(set.getId());
                    }
                    if (value.disks.size() > 8) {
                        throw new IllegalArgumentException("A manual member supports up to eight disk plans");
                    }
                    for (final var disk : value.disks) {
                        rules.addDisks(diskPlan(disk));
                        header.setDiskPlanCount(header.getDiskPlanCount() + 1);
                    }
                    ids[member] = add(fallback(value.name, name + "-" + (member + 1)), value.resources, rules);
                }
                requests[unit++] = new Request(ids);
            }
        }

        private int add(String name, Map<String, Double> amounts, Constraints.Builder rules) {
            final int id = resources.size() + 1;
            final var allocation = Alloc.newBuilder().setId(id);
            final var sorted = new TreeMap<Integer, Double>();
            for (final var entry : amounts.entrySet()) {
                final ResourceType type;
                try {
                    type = ResourceType.valueOf(entry.getKey());
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Unknown resource dimension: " + entry.getKey());
                }
                final int dimension = type.getNumber();
                final boolean deviceDimension = dimension >= 18;
                final boolean diskInventory = rules.getKind() == Kind.DISK;
                if (dimension < 1 || dimension > 20 || diskInventory != deviceDimension) {
                    throw new IllegalArgumentException("DEVICE_* dimensions belong to disks; other dimensions to hosts or workloads");
                }
                final double amount = amount(entry.getValue());
                if (rules.getKind() == Kind.HOST && (dimension == 11 || dimension == 17)
                        && (amount > 64 || amount != Math.rint(amount))) {
                    throw new IllegalArgumentException("A manual host supports 0–64 whole GPUs of each type");
                }
                if (dimension == 18 && amount != Math.rint(amount)) {
                    throw new IllegalArgumentException("Device extents must be whole numbers");
                }
                sorted.put(dimension, amount);
            }
            sorted.forEach((type, amount) -> allocation.addType(type).addAmount(amount));
            header.setAllocValueCount(header.getAllocValueCount() + sorted.size());
            allocations.add(Record.newBuilder().setAlloc(allocation).build());
            constraints.add(Record.newBuilder().setConstraints(rules.setId(id)).build());
            resources.add(Record.newBuilder().setResource(Resource.newBuilder().setId(id).setAlloc(id).setConstraints(id)).build());
            names.put(id, name(name));
            return id;
        }

        private int[] events() {
            final List<Event> source = config.events == null
                    ? config.requests.stream().map(unit -> new Event("create", unit.name)).toList() : config.events;
            final var result = new int[source.size()];
            final var active = new boolean[requests.length];
            for (int index = 0; index < result.length; index++) {
                final var event = source.get(index);
                final var unit = unitIds.get(event.request);
                if (unit == null) {
                    throw new IllegalArgumentException("Unknown request in event: " + event.request);
                }
                final boolean create;
                if ("create".equals(event.kind)) {
                    create = true;
                } else if ("delete".equals(event.kind)) {
                    create = false;
                } else {
                    throw new IllegalArgumentException("Event kind must be create or delete");
                }
                if (active[unit] == create) {
                    throw new IllegalArgumentException("Invalid request lifetime at event " + (index + 1));
                }
                active[unit] = create;
                result[index] = create ? unit + 1 : -unit - 1;
            }
            return result;
        }

        private Request[] catalogue() {
            if (config.catalogue == null) {
                final int size = Math.min(32, requests.length);
                final var result = new Request[size];
                for (int index = 0; index < size; index++) {
                    result[index] = requests[(int) ((long) index * requests.length / size)];
                }
                return result;
            }
            final var selected = new ArrayList<Request>();
            for (final var name : config.catalogue) {
                final var unit = unitIds.get(name);
                if (unit == null || selected.contains(requests[unit])) {
                    throw new IllegalArgumentException("Catalogue must contain distinct known request names");
                }
                selected.add(requests[unit]);
            }
            return selected.toArray(Request[]::new);
        }

        private List<Record> records() {
            header.setResourceCount(resources.size()).setAllocCount(allocations.size())
                    .setConstraintCount(constraints.size()).setHostCount(config.hosts.size())
                    .setCandidateCount(candidates.size() + 1);
            final var result = new ArrayList<Record>();
            result.add(Record.newBuilder().setHeader(header).build());
            result.addAll(allocations);
            result.addAll(constraints);
            result.addAll(candidates);
            result.addAll(resources);
            return result;
        }

        private int host(String name) {
            final var host = hostIds.get(name);
            if (host == null) {
                throw new IllegalArgumentException("Unknown host: " + name);
            }
            return host;
        }
    }

    private static PlacementDatasetProtos.DiskPlan diskPlan(DiskPlan disk) {
        if (disk.extents < 0 || disk.extents > 1_000_000_000) {
            throw new IllegalArgumentException("Disk plan extents must be between 0 and 1000000000");
        }
        final Layout layout;
        try {
            layout = Layout.valueOf(disk.layout.toUpperCase(Locale.ROOT));
            if (layout == Layout.UNRECOGNIZED) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown disk layout: " + disk.layout);
        }
        final var result = PlacementDatasetProtos.DiskPlan.newBuilder().setMedia(media(disk.media))
                .setLayout(layout).setExtents(disk.extents).setSpindles(amount(disk.spindles))
                .setMinDevices(1).setMaxDevices(32).setMinStripes(1).setMaxStripes(32)
                .setFlags(disk.exclusive ? 1 : 0);
        if (disk.iops != null) {
            result.setIops(amount(disk.iops));
        }
        return result.build();
    }

    private static Media media(String name) {
        try {
            final var media = Media.valueOf(name.toUpperCase(Locale.ROOT));
            if (media == Media.UNRECOGNIZED || media == Media.MEDIA_UNSPECIFIED) {
                throw new IllegalArgumentException();
            }
            return media;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown disk media: " + name);
        }
    }

    private static Kind kind(String name) {
        if ("POD".equalsIgnoreCase(name)) {
            return Kind.POD;
        }
        if ("SHARD".equalsIgnoreCase(name)) {
            return Kind.SHARD;
        }
        throw new IllegalArgumentException("Manual request members must be POD or SHARD");
    }

    private static double amount(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1_000_000_000_000.0) {
            throw new IllegalArgumentException("Resource amounts must be finite numbers between 0 and 1000000000000");
        }
        return value;
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Names must contain 1–128 characters");
        }
        return value;
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, Double> map(Map<String, Double> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static MessageDigest hash() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
