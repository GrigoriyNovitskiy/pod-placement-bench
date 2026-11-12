package one.cloud.benchmark.algorithms.rl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.GsonBuilder;

import one.cloud.benchmark.dataset.Dataset;
import one.cloud.benchmark.dataset.DatasetReader;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Candidates;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Stream;
import one.cloud.benchmark.model.Request;
import one.cloud.benchmark.runner.Scenario;

import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Kind.DISK_VALUE;
import static one.cloud.benchmark.dataset.PlacementDatasetProtos.Relation.COLOCATE_VALUE;

public final class CloudSubset {
    private CloudSubset() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Expected SOURCE_DIR OUTPUT_DIR HOST_COUNT SEED");
        }
        export(Path.of(arguments[0]), Path.of(arguments[1]), Integer.parseInt(arguments[2]),
                Long.parseLong(arguments[3]));
    }

    static void export(Path source, Path output, int hostCount, long seed) throws Exception {
        if (source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || Files.exists(output) && Files.isSameFile(source, output)) {
            throw new IllegalArgumentException("Output must differ from the original dataset directory");
        }
        final var snapshot = source.resolve("snapshot.pb");
        final var operations = source.resolve("operations.pb");
        final var data = Dataset.load(snapshot, operations);
        final var original = Scenario.from(data);
        final var hosts = selectHosts(data, hostCount, seed);
        final var selection = eligible(data, original.requests, hosts);
        final var kept = selection.resources;
        final int[] resources = dense(kept, 0);
        if (Arrays.stream(original.requests).noneMatch(request -> kept[request.resource(0)])) {
            throw new IllegalArgumentException("Host subset has no complete eligible atomic requests; try another seed");
        }
        final var usedAllocations = new boolean[data.allocations.offset.length - 1];
        final var usedRules = new boolean[data.rules.kind.length];
        int maxCandidate = 1;
        for (int resource = 1; resource < resources.length; resource++) {
            if (kept[resource]) {
                usedAllocations[data.alloc[resource]] = true;
                usedRules[data.constraints[resource]] = true;
                maxCandidate = Math.max(maxCandidate, data.rules.candidates[data.constraints[resource]]);
            }
        }
        final int[] allocations = dense(usedAllocations, 0);
        final int[] rules = dense(usedRules, 0);
        final var usedCandidates = new boolean[maxCandidate + 1];
        for (int rule = 1; rule < usedRules.length; rule++) {
            if (usedRules[rule] && data.rules.candidates[rule] > 1) {
                usedCandidates[data.rules.candidates[rule]] = true;
            }
        }
        final int[] candidateIds = dense(usedCandidates, 1);
        candidateIds[1] = 1;
        final var header = Header.newBuilder().setVersion(2).setStream(Stream.MODEL).setDatasetId(data.id)
                .setHostCount(hostCount).setResourceCount(max(resources)).setAllocCount(max(allocations))
                .setConstraintCount(max(rules)).setCandidateCount(max(candidateIds))
                .setDimensionCount(data.dimensionCount).setExtentBytes(data.extentBytes);
        final var records = new ArrayList<Record>();
        DatasetReader.read(snapshot, record -> {
            if (record.hasAlloc() && allocations[record.getAlloc().getId()] != 0) {
                final var value = record.getAlloc().toBuilder().setId(allocations[record.getAlloc().getId()]);
                records.add(Record.newBuilder().setAlloc(value).build());
                header.setAllocValueCount(header.getAllocValueCount() + value.getTypeCount());
            } else if (record.hasConstraints() && rules[record.getConstraints().getId()] != 0) {
                final var value = record.getConstraints().toBuilder();
                value.setId(rules[value.getId()]).setParent(resources[value.getParent()])
                        .setPinnedHost(resources[value.getPinnedHost()]);
                value.setCandidates(candidateIds[value.getCandidates()]);
                for (int link = 0; link < value.getLinksCount(); link++) {
                    if (value.getLinks(link).getRelationValue() == COLOCATE_VALUE) {
                        value.setLinks(link, value.getLinks(link).toBuilder()
                                .setTarget(resources[value.getLinks(link).getTarget()]));
                    }
                }
                records.add(Record.newBuilder().setConstraints(value).build());
                header.setLimitCount(header.getLimitCount() + value.getLimitsCount())
                        .setLinkCount(header.getLinkCount() + value.getLinksCount())
                        .setDiskPlanCount(header.getDiskPlanCount() + value.getDisksCount())
                        .setDomainCount(header.getDomainCount() + value.getDomainsCount());
            } else if (record.hasResource() && resources[record.getResource().getId()] != 0) {
                final var value = record.getResource();
                records.add(Record.newBuilder().setResource(value.toBuilder().setId(resources[value.getId()])
                        .setAlloc(allocations[value.getAlloc()]).setConstraints(rules[value.getConstraints()])).build());
            }
        });
        for (int candidate = 2; candidate < candidateIds.length; candidate++) {
            if (candidateIds[candidate] != 0) {
                final var value = Candidates.newBuilder().setId(candidateIds[candidate]);
                for (int host = 1; host <= data.hostCount; host++) {
                    if (hosts[host] && data.candidates.allows(candidate, host)) {
                        value.addHost(resources[host]);
                    }
                }
                records.add(Record.newBuilder().setCandidates(value).build());
                header.setCandidateHostCount(header.getCandidateHostCount() + value.getHostCount());
            }
        }
        final var filtered = new ArrayList<Record>();
        DatasetReader.read(operations, record -> {
            if (record.hasOperation() && kept[record.getOperation().getResource()]) {
                filtered.add(Record.newBuilder().setOperation(record.getOperation().toBuilder()
                        .setResource(resources[record.getOperation().getResource()])).build());
            }
        });
        Files.createDirectories(output);
        write(output.resolve("snapshot.pb"), header.build(), records);
        write(output.resolve("operations.pb"), Header.newBuilder().setVersion(2).setStream(Stream.OPERATIONS)
                .setDatasetId(data.id).setResourceCount(max(resources)).setOperationCount(filtered.size())
                .setSyntheticOperations(true).build(), filtered);
        final var projected = Scenario.from(Dataset.load(output.resolve("snapshot.pb"), output.resolve("operations.pb")));
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("formatVersion", 1);
        manifest.put("projection", "native-hosts-v1");
        manifest.put("source", source.toRealPath().toString());
        manifest.put("sourceDatasetId", Long.toUnsignedString(data.id));
        manifest.put("sourceSnapshotSha256", sha256(snapshot));
        manifest.put("sourceOperationsSha256", sha256(operations));
        manifest.put("sourceScenarioDigest", original.digest);
        manifest.put("seed", seed);
        final var selected = new ArrayList<Integer>();
        for (int host = 1; host <= data.hostCount; host++) {
            if (hosts[host]) {
                selected.add(host);
            }
        }
        manifest.put("selectedHostIds", selected);
        manifest.put("sourceHosts", data.hostCount);
        manifest.put("sourceRequests", original.requests.length);
        manifest.put("retainedRequests", projected.requests.length);
        manifest.put("droppedRequests", original.requests.length - projected.requests.length);
        manifest.put("droppedByLocationOrFilters", original.requests.length - selection.initialRequests);
        manifest.put("droppedByDependencyClosure", selection.initialRequests - projected.requests.length);
        manifest.put("sourceOperations", data.operations.resource.length);
        manifest.put("retainedOperations", filtered.size());
        manifest.put("scenarioDigest", projected.digest);
        manifest.put("snapshotSha256", sha256(output.resolve("snapshot.pb")));
        manifest.put("operationsSha256", sha256(output.resolve("operations.pb")));
        Files.writeString(output.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n");
    }

    private static boolean[] selectHosts(Dataset data, int count, long seed) {
        if (count < 1 || count > data.hostCount) {
            throw new IllegalArgumentException("Host count must be between 1 and the source host count");
        }
        final var gpu = new ArrayList<Integer>();
        final var cpu = new ArrayList<Integer>();
        for (int host = 1; host <= data.hostCount; host++) {
            final int allocation = data.alloc[host];
            boolean hasGpu = false;
            for (int value = data.allocations.offset[allocation]; value < data.allocations.offset[allocation + 1]; value++) {
                final int dimension = data.allocations.type[value];
                hasGpu |= (dimension == 11 || dimension == 17) && data.allocations.amount[value] > 0;
            }
            (hasGpu ? gpu : cpu).add(host);
        }
        final var random = new Random(seed);
        Collections.shuffle(gpu, random);
        Collections.shuffle(cpu, random);
        final int gpuCount = Math.min(gpu.size(), Math.max(count - cpu.size(), Math.max(1, count / 4)));
        final var chosen = new boolean[data.hostCount + 1];
        for (final int host : gpu.subList(0, gpuCount)) {
            chosen[host] = true;
        }
        for (final int host : cpu.subList(0, count - gpuCount)) {
            chosen[host] = true;
        }
        return chosen;
    }

    private record Selection(boolean[] resources, int initialRequests) {
    }

    private static Selection eligible(Dataset data, Request[] requests, boolean[] hosts) {
        final var placed = new boolean[data.alloc.length];
        final var outside = new boolean[placed.length];
        for (int row = 0; row < data.snapshot.resource.length; row++) {
            final int resource = data.snapshot.resource[row];
            placed[resource] = true;
            outside[resource] |= !hosts[data.snapshot.host[row]];
        }
        final var kept = new boolean[placed.length];
        for (int resource = 1; resource < kept.length; resource++) {
            final int rule = data.constraints[resource];
            kept[resource] = resource <= data.hostCount ? hosts[resource]
                    : data.rules.kind[rule] <= DISK_VALUE && hosts[data.rules.parent[rule]];
        }
        int initialRequests = 0;
        for (final var request : requests) {
            boolean allowed = true;
            for (int member = 0; member < request.size(); member++) {
                final int resource = request.resource(member);
                final int rule = data.constraints[resource];
                allowed &= placed[resource] && !outside[resource]
                        && (data.rules.pinnedHost[rule] == 0 || hosts[data.rules.pinnedHost[rule]]);
                boolean candidate = false;
                for (int host = 1; host <= data.hostCount && !candidate; host++) {
                    candidate = hosts[host] && data.candidates.allows(data.rules.candidates[rule], host);
                }
                allowed &= candidate;
            }
            for (int member = 0; member < request.size(); member++) {
                kept[request.resource(member)] = allowed;
            }
            initialRequests += allowed ? 1 : 0;
        }
        final var totals = groups(data, null);
        boolean changed;
        do {
            changed = false;
            final var retained = groups(data, kept);
            for (final var request : requests) {
                if (!kept[request.resource(0)]) {
                    continue;
                }
                boolean closed = true;
                for (int member = 0; member < request.size(); member++) {
                    final int rule = data.constraints[request.resource(member)];
                    closed &= data.rules.parent[rule] == 0 || kept[data.rules.parent[rule]];
                    for (int link = data.rules.linkOffset[rule]; link < data.rules.linkOffset[rule + 1]; link++) {
                        final int target = data.rules.linkTarget[link];
                        closed &= data.rules.linkKind[link] == COLOCATE_VALUE ? kept[target]
                                : retained.getOrDefault(target, 0).equals(totals.getOrDefault(target, 0));
                    }
                }
                if (!closed) {
                    changed = true;
                    for (int member = 0; member < request.size(); member++) {
                        kept[request.resource(member)] = false;
                    }
                }
            }
        } while (changed);
        return new Selection(kept, initialRequests);
    }

    private static Map<Integer, Integer> groups(Dataset data, boolean[] kept) {
        final var counts = new HashMap<Integer, Integer>();
        for (int resource = 1; resource < data.alloc.length; resource++) {
            final int rule = data.constraints[resource];
            if ((kept == null || kept[resource]) && data.rules.kind[rule] > DISK_VALUE && data.rules.group[rule] != 0) {
                counts.merge(data.rules.group[rule], 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int[] dense(boolean[] used, int reserved) {
        final var ids = new int[used.length];
        int next = reserved;
        for (int old = 1; old < used.length; old++) {
            if (used[old]) {
                ids[old] = ++next;
            }
        }
        return ids;
    }

    private static int max(int[] ids) {
        return Arrays.stream(ids).max().orElse(0);
    }

    private static void write(Path path, Header header, List<Record> records) throws IOException {
        try (final var stream = new BufferedOutputStream(Files.newOutputStream(path))) {
            Record.newBuilder().setHeader(header).build().writeDelimitedTo(stream);
            for (final var record : records) {
                record.writeDelimitedTo(stream);
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        try (final var stream = Files.newInputStream(path)) {
            final var buffer = new byte[64 * 1024];
            for (int read; (read = stream.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
