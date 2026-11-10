package one.cloud.benchmark.dataset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Header;
import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;

public final class Dataset {
    public final long id;
    public final int hostCount;
    public final int dimensionCount;
    public final long extentBytes;
    public final boolean hasSnapshot;
    public final int[] alloc;
    public final int[] constraints;
    public final Allocs allocations;
    public final Rules rules;
    public final CandidateSets candidates;
    public final Placements snapshot;
    public final Operations operations;

    private Dataset(Decoder decoder, Operations operations) {
        id = decoder.header.getDatasetId();
        hostCount = decoder.header.getHostCount();
        dimensionCount = decoder.header.getDimensionCount();
        extentBytes = decoder.header.getExtentBytes();
        hasSnapshot = decoder.header.getStream() == PlacementDatasetProtos.Stream.SNAPSHOT;
        alloc = decoder.alloc;
        constraints = decoder.constraints;
        allocations = decoder.allocations;
        rules = decoder.rules;
        candidates = decoder.candidates;
        snapshot = decoder.snapshot;
        this.operations = operations;
    }

    public static Dataset load(Path model) throws IOException {
        return load(model, null);
    }

    public static Dataset load(Path model, Path operations) throws IOException {
        try {
            final var decoder = new Decoder();
            DatasetReader.read(model, decoder::accept);
            decoder.finish();
            final var trace = operations == null ? Operations.EMPTY : readOperations(operations, decoder.header);
            return new Dataset(decoder, trace);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid placement dataset: " + failure.getMessage(), failure);
        }
    }

    private static Operations readOperations(Path path, Header model) throws IOException {
        final Header[] header = {null};
        final Operations[] result = {null};
        final int[] next = {0};
        DatasetReader.read(path, record -> {
            if (header[0] == null) {
                if (!record.hasHeader()) {
                    throw new IllegalArgumentException("Operation stream must start with a header");
                }
                final var value = record.getHeader();
                if (value.getVersion() != 2 || value.getStream() != PlacementDatasetProtos.Stream.OPERATIONS
                        || value.getDatasetId() != model.getDatasetId() || value.getResourceCount() != model.getResourceCount()) {
                    throw new IllegalArgumentException("Operation stream does not match the model");
                }
                header[0] = value;
                result[0] = new Operations(size(value.getOperationCount()), value.getSyntheticOperations(), value.getReplayFromSnapshot());
                return;
            }
            if (!record.hasOperation() || next[0] == result[0].resource.length) {
                throw new IllegalArgumentException("Unexpected operation record");
            }
            final var value = record.getOperation();
            final int index = next[0]++;
            final int resource = reference(value.getResource(), model.getResourceCount(), false);
            if (value.getTime() < 0 || value.getGeneration() <= 0 || index != 0 && value.getTime() < result[0].time[index - 1]) {
                throw new IllegalArgumentException("Invalid operation order or generation");
            }
            result[0].resource[index] = resource;
            result[0].generation[index] = value.getGeneration();
            result[0].time[index] = value.getTime();
            result[0].create[index] = value.getCreate();
        });
        if (header[0] == null || next[0] != result[0].resource.length) {
            throw new IOException("Incomplete operation stream");
        }
        return result[0];
    }

    public static final class Allocs {
        public final int[] offset;
        public final int[] type;
        public final double[] amount;

        private Allocs(Header header) {
            offset = new int[size(header.getAllocCount()) + 2];
            type = new int[size(header.getAllocValueCount())];
            amount = new double[type.length];
        }
    }

    public static final class Rules {
        public final int[] kind, parent, ordinal, media, alternativeMedia, candidates, pinnedHost, group, flags;
        public final double[] cpuPower, alternativeSpindles;
        public final int[] limitOffset, limitDomain, limitMax;
        public final boolean[] limitHard;
        public final int[] linkOffset, linkTarget, linkDomain, linkKind;
        public final int[] diskOffset, diskMedia, diskLayout, minDevices, maxDevices, minStripes, maxStripes, diskFlags, metadataExtents;
        public final long[] diskExtents;
        public final double[] diskSpindles, diskIops, diskIopsCap;
        public final int[] domainOffset, domainLevel, domainValue;

        private Rules(Header header) {
            final int count = size(header.getConstraintCount()) + 1;
            kind = new int[count];
            parent = new int[count];
            ordinal = new int[count];
            media = new int[count];
            alternativeMedia = new int[count];
            alternativeSpindles = new double[count];
            candidates = new int[count];
            pinnedHost = new int[count];
            group = new int[count];
            flags = new int[count];
            cpuPower = new double[count];
            Arrays.fill(cpuPower, 1);
            limitOffset = new int[count + 1];
            limitDomain = new int[size(header.getLimitCount())];
            limitMax = new int[limitDomain.length];
            limitHard = new boolean[limitDomain.length];
            linkOffset = new int[count + 1];
            linkTarget = new int[size(header.getLinkCount())];
            linkDomain = new int[linkTarget.length];
            linkKind = new int[linkTarget.length];
            diskOffset = new int[count + 1];
            diskMedia = new int[size(header.getDiskPlanCount())];
            diskLayout = new int[diskMedia.length];
            diskExtents = new long[diskMedia.length];
            diskSpindles = new double[diskMedia.length];
            diskIops = new double[diskMedia.length];
            Arrays.fill(diskIops, Double.NaN);
            diskIopsCap = new double[diskMedia.length];
            Arrays.fill(diskIopsCap, Double.NaN);
            minDevices = new int[diskMedia.length];
            maxDevices = new int[diskMedia.length];
            minStripes = new int[diskMedia.length];
            maxStripes = new int[diskMedia.length];
            diskFlags = new int[diskMedia.length];
            metadataExtents = new int[diskMedia.length];
            domainOffset = new int[count + 1];
            domainLevel = new int[size(header.getDomainCount())];
            domainValue = new int[domainLevel.length];
        }
    }

    public static final class CandidateSets {
        private final long[][] bits;
        private final int[][] sparse;
        private final int hostCount;

        private CandidateSets(Header header) {
            hostCount = size(header.getHostCount());
            bits = new long[size(header.getCandidateCount()) + 1][];
            sparse = new int[bits.length][];
        }

        public boolean allows(int set, int host) {
            if (host <= 0 || host > hostCount) {
                return false;
            }
            if (set < 2) {
                return set == 0;
            }
            final var words = bits[set];
            return words == null ? Arrays.binarySearch(sparse[set], host) >= 0
                    : (words[host >>> 6] & 1L << host) != 0;
        }
    }

    public static final class Placements {
        public final int[] resource, host, numa, alloc, deviceOffset, device;
        public final long[] extents;
        public final double[] spindles, iops;

        private Placements(Header header) {
            resource = new int[size(header.getPlacementCount())];
            host = new int[resource.length];
            numa = new int[resource.length];
            alloc = new int[resource.length];
            deviceOffset = new int[resource.length + 1];
            device = new int[size(header.getDeviceUseCount())];
            extents = new long[device.length];
            spindles = new double[device.length];
            iops = new double[device.length];
            Arrays.fill(iops, Double.NaN);
        }
    }

    public static final class Operations {
        private static final Operations EMPTY = new Operations(0, false, false);
        public final long[] time;
        public final int[] resource, generation;
        public final boolean[] create;
        public final boolean synthetic, replayFromSnapshot;

        private Operations(int count, boolean synthetic, boolean replayFromSnapshot) {
            if (17L * count > Runtime.getRuntime().maxMemory() / 2) {
                throw new IllegalArgumentException("Operation arrays exceed half of the configured Java heap");
            }
            this.synthetic = synthetic;
            this.replayFromSnapshot = replayFromSnapshot;
            time = new long[count];
            resource = new int[count];
            generation = new int[count];
            create = new boolean[count];
        }
    }

    private static final class Decoder {
        private Header header;
        private int[] alloc, constraints;
        private Allocs allocations;
        private Rules rules;
        private CandidateSets candidates;
        private Placements snapshot;
        private int nextAlloc, nextConstraints, nextResource, nextCandidates = 1, nextPlacement;
        private int allocValues, limits, links, disks, domains, deviceUses, candidateHosts;

        private void accept(Record record) {
            if (header == null) {
                if (!record.hasHeader()) {
                    throw new IllegalArgumentException("Dataset must start with a header");
                }
                header = record.getHeader();
                initialize();
                return;
            }
            switch (record.getPayloadCase()) {
                case ALLOC -> allocation(record.getAlloc());
                case CONSTRAINTS -> constraints(record.getConstraints());
                case CANDIDATES -> candidates(record.getCandidates());
                case RESOURCE -> resource(record.getResource());
                case PLACEMENT -> placement(record.getPlacement());
                default -> throw new IllegalArgumentException("Unexpected dataset record");
            }
        }

        private void initialize() {
            if (header.getVersion() != 2 || header.getStream() == PlacementDatasetProtos.Stream.OPERATIONS
                    || header.getStream() == PlacementDatasetProtos.Stream.UNRECOGNIZED
                    || header.getCandidateCount() < 1 || header.getHostCount() > header.getResourceCount()
                    || header.getDimensionCount() <= 0 || header.getDimensionCount() > 1024 || header.getExtentBytes() <= 0) {
                throw new IllegalArgumentException("Invalid dataset header");
            }
            final long bytes = 8L * size(header.getResourceCount()) + 8L * size(header.getAllocCount())
                    + 84L * size(header.getConstraintCount()) + 16L * size(header.getCandidateCount())
                    + 20L * size(header.getPlacementCount()) + 12L * size(header.getAllocValueCount())
                    + 9L * size(header.getLimitCount()) + 12L * size(header.getLinkCount())
                    + 64L * size(header.getDiskPlanCount()) + 8L * size(header.getDomainCount())
                    + 28L * size(header.getDeviceUseCount()) + 4L * size(header.getCandidateHostCount());
            if (bytes > Runtime.getRuntime().maxMemory() / 2) {
                throw new IllegalArgumentException("Dataset arrays exceed half of the configured Java heap");
            }
            alloc = new int[size(header.getResourceCount()) + 1];
            constraints = new int[alloc.length];
            allocations = new Allocs(header);
            rules = new Rules(header);
            candidates = new CandidateSets(header);
            snapshot = new Placements(header);
        }

        private void allocation(PlacementDatasetProtos.Alloc value) {
            final int id = next(value.getId(), ++nextAlloc, header.getAllocCount());
            if (value.getTypeCount() != value.getAmountCount()) {
                throw new IllegalArgumentException("Allocation dimensions and values have different lengths");
            }
            allocations.offset[id] = allocValues;
            int previous = 0;
            for (int index = 0; index < value.getTypeCount(); index++) {
                checkSpace(allocValues, allocations.type.length);
                final int type = reference(value.getType(index), header.getDimensionCount(), false);
                if (type <= previous) {
                    throw new IllegalArgumentException("Allocation dimensions must be sorted and unique");
                }
                allocations.type[allocValues] = type;
                allocations.amount[allocValues++] = nonnegative(value.getAmount(index));
                previous = type;
            }
            allocations.offset[id + 1] = allocValues;
        }

        private void constraints(PlacementDatasetProtos.Constraints value) {
            final int id = next(value.getId(), ++nextConstraints, header.getConstraintCount());
            rules.kind[id] = enumValue(value.getKindValue(), PlacementDatasetProtos.Kind.SHARD_VALUE);
            rules.parent[id] = reference(value.getParent(), header.getResourceCount(), true);
            rules.ordinal[id] = nonnegative(value.getOrdinal());
            rules.media[id] = enumValue(value.getMediaValue(), PlacementDatasetProtos.Media.ROOTFS_VALUE);
            rules.alternativeMedia[id] = enumValue(value.getAlternativeMediaValue(), PlacementDatasetProtos.Media.ROOTFS_VALUE);
            rules.alternativeSpindles[id] = nonnegative(value.getAlternativeSpindles());
            rules.candidates[id] = reference(value.getCandidates(), header.getCandidateCount(), true);
            rules.pinnedHost[id] = reference(value.getPinnedHost(), header.getHostCount(), true);
            rules.group[id] = nonnegative(value.getGroup());
            rules.flags[id] = value.getFlags();
            if (value.getCpuPower() != 0) {
                rules.cpuPower[id] = positive(value.getCpuPower());
            }
            rules.limitOffset[id] = limits;
            for (final var limit : value.getLimitsList()) {
                checkSpace(limits, rules.limitDomain.length);
                rules.limitDomain[limits] = positive(limit.getDomain());
                rules.limitMax[limits] = nonnegative(limit.getMax());
                rules.limitHard[limits++] = limit.getHard();
            }
            rules.limitOffset[id + 1] = limits;
            rules.linkOffset[id] = links;
            for (final var link : value.getLinksList()) {
                checkSpace(links, rules.linkTarget.length);
                rules.linkTarget[links] = positive(link.getTarget());
                rules.linkDomain[links] = positive(link.getDomain());
                rules.linkKind[links++] = enumValue(link.getRelationValue(), PlacementDatasetProtos.Relation.SHARED_GROUP_VALUE);
            }
            rules.linkOffset[id + 1] = links;
            rules.diskOffset[id] = disks;
            for (final var disk : value.getDisksList()) {
                checkSpace(disks, rules.diskMedia.length);
                rules.diskMedia[disks] = enumValue(disk.getMediaValue(), PlacementDatasetProtos.Media.ROOTFS_VALUE);
                rules.diskLayout[disks] = enumValue(disk.getLayoutValue(), PlacementDatasetProtos.Layout.RAID4_VALUE);
                rules.diskExtents[disks] = nonnegative(disk.getExtents());
                rules.diskSpindles[disks] = nonnegative(disk.getSpindles());
                if (disk.hasIops()) {
                    rules.diskIops[disks] = nonnegative(disk.getIops());
                }
                if (disk.hasPerDeviceIopsCap()) {
                    rules.diskIopsCap[disks] = nonnegative(disk.getPerDeviceIopsCap());
                }
                rules.minDevices[disks] = nonnegative(disk.getMinDevices());
                rules.maxDevices[disks] = nonnegative(disk.getMaxDevices());
                rules.minStripes[disks] = nonnegative(disk.getMinStripes());
                rules.maxStripes[disks] = nonnegative(disk.getMaxStripes());
                rules.diskFlags[disks] = disk.getFlags();
                rules.metadataExtents[disks++] = nonnegative(disk.getMetadataExtents());
            }
            rules.diskOffset[id + 1] = disks;
            rules.domainOffset[id] = domains;
            int previous = 0;
            for (final var domain : value.getDomainsList()) {
                checkSpace(domains, rules.domainLevel.length);
                final int level = positive(domain.getLevel());
                if (level <= previous) {
                    throw new IllegalArgumentException("Domain levels must be sorted and unique");
                }
                rules.domainLevel[domains] = level;
                rules.domainValue[domains++] = positive(domain.getValue());
                previous = level;
            }
            rules.domainOffset[id + 1] = domains;
        }

        private void candidates(PlacementDatasetProtos.Candidates value) {
            final int id = next(value.getId(), ++nextCandidates, header.getCandidateCount());
            final int count = value.getHostCount();
            candidateHosts += count;
            final int words = (header.getHostCount() >>> 6) + 1;
            final boolean dense = 8L * words < 4L * count;
            final var bits = dense ? new long[words] : null;
            final var sparse = dense ? null : new int[count];
            int previous = 0;
            for (int index = 0; index < count; index++) {
                final int host = reference(value.getHost(index), header.getHostCount(), false);
                if (host <= previous) {
                    throw new IllegalArgumentException("Candidate hosts must be sorted and unique");
                }
                if (dense) {
                    bits[host >>> 6] |= 1L << host;
                } else {
                    sparse[index] = host;
                }
                previous = host;
            }
            candidates.bits[id] = bits;
            candidates.sparse[id] = sparse;
        }

        private void resource(PlacementDatasetProtos.Resource value) {
            final int id = next(value.getId(), ++nextResource, header.getResourceCount());
            alloc[id] = reference(value.getAlloc(), header.getAllocCount(), true);
            constraints[id] = reference(value.getConstraints(), header.getConstraintCount(), true);
        }

        private void placement(PlacementDatasetProtos.Placement value) {
            checkSpace(nextPlacement, snapshot.resource.length);
            final int index = nextPlacement++;
            snapshot.resource[index] = reference(value.getResource(), header.getResourceCount(), false);
            snapshot.host[index] = reference(value.getHost(), header.getHostCount(), false);
            snapshot.numa[index] = reference(value.getNuma(), header.getResourceCount(), true);
            snapshot.alloc[index] = reference(value.getAlloc(), header.getAllocCount(), true);
            snapshot.deviceOffset[index] = deviceUses;
            for (final var device : value.getDevicesList()) {
                checkSpace(deviceUses, snapshot.device.length);
                snapshot.device[deviceUses] = reference(device.getDevice(), header.getResourceCount(), false);
                snapshot.extents[deviceUses] = nonnegative(device.getExtents());
                snapshot.spindles[deviceUses] = nonnegative(device.getSpindles());
                if (device.hasIops()) {
                    snapshot.iops[deviceUses] = nonnegative(device.getIops());
                }
                deviceUses++;
            }
            snapshot.deviceOffset[index + 1] = deviceUses;
        }

        private void finish() throws IOException {
            if (header == null || nextResource != header.getResourceCount() || nextAlloc != header.getAllocCount()
                    || nextConstraints != header.getConstraintCount() || nextCandidates != header.getCandidateCount()
                    || nextPlacement != header.getPlacementCount() || allocValues != allocations.type.length
                    || limits != rules.limitDomain.length || links != rules.linkTarget.length || disks != rules.diskMedia.length
                    || domains != rules.domainLevel.length || deviceUses != snapshot.device.length
                    || candidateHosts != header.getCandidateHostCount()) {
                throw new IOException("Dataset record counts do not match the header");
            }
            for (int resource = 1; resource < alloc.length; resource++) {
                final int profile = constraints[resource];
                final int kind = rules.kind[profile];
                if (resource <= hostCount() && kind != PlacementDatasetProtos.Kind.HOST_VALUE
                        || resource > hostCount() && kind == PlacementDatasetProtos.Kind.HOST_VALUE
                        || kind < PlacementDatasetProtos.Kind.GENERIC_VALUE || kind > PlacementDatasetProtos.Kind.SHARD_VALUE) {
                    throw new IOException("Invalid resource kind or host range");
                }
                if (kind == PlacementDatasetProtos.Kind.NUMA_VALUE || kind == PlacementDatasetProtos.Kind.DISK_VALUE) {
                    final int parent = rules.parent[profile];
                    if (parent <= 0 || parent > hostCount()) {
                        throw new IOException("Inventory resource has no host parent");
                    }
                }
                for (int link = rules.linkOffset[profile]; link < rules.linkOffset[profile + 1]; link++) {
                    if (rules.linkKind[link] == PlacementDatasetProtos.Relation.COLOCATE_VALUE
                            && rules.linkTarget[link] >= alloc.length) {
                        throw new IOException("Colocation link references a missing resource");
                    }
                }
            }
            for (int index = 0; index < snapshot.resource.length; index++) {
                final int numa = snapshot.numa[index];
                if (numa != 0 && (rules.kind[constraints[numa]] != PlacementDatasetProtos.Kind.NUMA_VALUE
                        || rules.parent[constraints[numa]] != snapshot.host[index])) {
                    throw new IOException("Placement NUMA node belongs to another host");
                }
                for (int use = snapshot.deviceOffset[index]; use < snapshot.deviceOffset[index + 1]; use++) {
                    final int device = snapshot.device[use];
                    if (rules.kind[constraints[device]] != PlacementDatasetProtos.Kind.DISK_VALUE
                            || rules.parent[constraints[device]] != snapshot.host[index]) {
                        throw new IOException("Placement device belongs to another host");
                    }
                }
            }
        }

        private int hostCount() {
            return header.getHostCount();
        }
    }

    private static int next(int id, int expected, int max) {
        if (id != expected || id <= 0 || id > max) {
            throw new IllegalArgumentException("Record IDs must be dense and ordered");
        }
        return id;
    }

    private static int reference(int value, int max, boolean absent) {
        if (value < 0 || value > max || !absent && value == 0) {
            throw new IllegalArgumentException("Reference is outside the declared range");
        }
        return value;
    }

    private static int size(int value) {
        if (value < 0 || value > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Invalid array size");
        }
        return value;
    }

    private static int enumValue(int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("Unknown model enum value");
        }
        return value;
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Expected a positive integer");
        }
        return value;
    }

    private static double positive(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Expected a finite positive number");
        }
        return value;
    }

    private static double nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Expected a finite nonnegative number");
        }
        return value;
    }

    private static int nonnegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Expected a nonnegative integer");
        }
        return value;
    }

    private static long nonnegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Expected a nonnegative quantity");
        }
        return value;
    }

    private static void checkSpace(int next, int count) {
        if (next >= count) {
            throw new IllegalArgumentException("Record exceeds the declared element count");
        }
    }
}
