package one.cloud.benchmark.algorithms.fitcount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import one.cloud.benchmark.model.ClusterSpec;
import one.cloud.benchmark.model.Request;

final class MaskedFitCountOrder {
    static final int PROFILE_LIMIT = 4096;
    static final long WORKSPACE_LIMIT = 64L * 1024 * 1024;
    private final FitCountCache cache;
    private final Profile[] profiles;
    private final Map<FitCountCache.Sample, Profile[]> families;
    private final long[] totals, generations;
    private final int[] pending, ready;
    private final double[] bounds;
    private final int cap;
    final int[] losses;
    final int distinctMasks, emptyProfiles;
    final long workspacePeak;
    private int pendingCount, readyCount;
    private long rebuilds, hostDeltas, membershipChecks, familyRefreshes;

    private MaskedFitCountOrder(FitCountCache cache, Compiler compiler, int cap) {
        this.cache = cache;
        this.cap = cap;
        profiles = compiler.profiles.values().toArray(Profile[]::new);
        families = new HashMap<>();
        final var grouped = new HashMap<FitCountCache.Sample, ArrayList<Profile>>();
        for (final var profile : profiles) {
            grouped.computeIfAbsent(profile.family, ignored -> new ArrayList<>()).add(profile);
        }
        for (final var entry : grouped.entrySet()) {
            families.put(entry.getKey(), entry.getValue().toArray(Profile[]::new));
        }
        final int count = profiles.length;
        compiler.reserve(count, Long.BYTES);
        totals = new long[count];
        compiler.reserve(count, Long.BYTES);
        generations = new long[count];
        Arrays.fill(generations, -1);
        compiler.reserve(count, Integer.BYTES);
        pending = new int[count];
        compiler.reserve(count, Integer.BYTES);
        ready = new int[count];
        compiler.reserve(count, Double.BYTES);
        bounds = new double[count];
        compiler.reserve(compiler.maxRetainedWidth, Integer.BYTES);
        losses = new int[compiler.maxRetainedWidth];
        distinctMasks = compiler.masks.size();
        emptyProfiles = (int) Arrays.stream(profiles).filter(profile -> profile.mask == Mask.EMPTY).count();
        workspacePeak = compiler.peak;
    }

    void beginOrder() {
        readyCount = 0;
        pendingCount = profiles.length;
        for (int index = 0; index < profiles.length; index++) {
            final long changes = cache.generation() - generations[index];
            bounds[index] = generations[index] < 0 || changes > totals[index] / cap
                    ? 0 : (totals[index] - changes * cap) / (double) profiles[index].frequency;
            pending[index] = index;
        }
        for (int index = pendingCount / 2 - 1; index >= 0; index--) {
            siftDown(pending, pendingCount, index, true);
        }
    }

    Profile next() {
        while (pendingCount != 0 && (readyCount == 0 || bounds[pending[0]] <= weightedTotal(ready[0]))) {
            final int index = pending[0];
            pending[0] = pending[--pendingCount];
            if (pendingCount != 0) {
                siftDown(pending, pendingCount, 0, true);
            }
            cache.counts(profiles[index].family);
            int position = readyCount++;
            while (position > 0) {
                final int parent = (position - 1) / 2;
                if (compare(ready[parent], index, false) <= 0) {
                    break;
                }
                ready[position] = ready[parent];
                position = parent;
            }
            ready[position] = index;
        }
        if (readyCount == 0) {
            return null;
        }
        final int index = ready[0];
        ready[0] = ready[--readyCount];
        if (readyCount != 0) {
            siftDown(ready, readyCount, 0, false);
        }
        return profiles[index];
    }

    boolean allows(Profile profile, int host) {
        membershipChecks++;
        return profile.mask.allows(host);
    }

    void rebuilding(FitCountCache.Sample family) {
        final var members = families.get(family);
        if (members != null) {
            rebuilds++;
            for (final var profile : members) {
                totals[profile.index] = 0;
            }
        }
    }

    void hostChanged(FitCountCache.Sample family, int host, int oldCount, int newCount) {
        final var members = families.get(family);
        if (members != null) {
            hostDeltas++;
            for (final var profile : members) {
                if (allows(profile, host)) {
                    totals[profile.index] += newCount - oldCount;
                }
            }
        }
    }

    void refreshed(FitCountCache.Sample family, long generation) {
        final var members = families.get(family);
        if (members != null) {
            familyRefreshes++;
            for (final var profile : members) {
                generations[profile.index] = generation;
            }
        }
    }

    long total(Profile profile) {
        cache.counts(profile.family);
        return totals[profile.index];
    }

    Map<String, Long> counters() {
        return Map.ofEntries(Map.entry("logical_profiles", (long) profiles.length),
                Map.entry("physical_families", (long) families.size()),
                Map.entry("distinct_masks", (long) distinctMasks),
                Map.entry("empty_mask_profiles", (long) emptyProfiles),
                Map.entry("masked_row_rebuilds", rebuilds), Map.entry("masked_host_deltas", hostDeltas),
                Map.entry("masked_membership_checks", membershipChecks),
                Map.entry("masked_family_refreshes", familyRefreshes),
                Map.entry("masked_workspace_payload_peak_bytes", workspacePeak),
                Map.entry("masked_profile_limit", 0L), Map.entry("masked_workspace_limit", 0L));
    }

    private double weightedTotal(int index) {
        return totals[index] / (double) profiles[index].frequency;
    }

    private int compare(int first, int second, boolean lowerBound) {
        final int comparison = Double.compare(lowerBound ? bounds[first] : weightedTotal(first),
                lowerBound ? bounds[second] : weightedTotal(second));
        return comparison == 0 ? Integer.compare(profiles[first].ordinal, profiles[second].ordinal) : comparison;
    }

    private void siftDown(int[] heap, int count, int index, boolean lowerBound) {
        final int value = heap[index];
        while (index < count / 2) {
            int child = index * 2 + 1;
            if (child + 1 < count && compare(heap[child + 1], heap[child], lowerBound) < 0) {
                child++;
            }
            if (compare(value, heap[child], lowerBound) <= 0) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = value;
    }

    static final class Profile {
        final FitCountCache.Sample family;
        final Mask mask;
        final int index, ordinal;
        int frequency = 1;

        Profile(FitCountCache.Sample family, Mask mask, int index, int ordinal) {
            this.family = family;
            this.mask = mask;
            this.index = index;
            this.ordinal = ordinal;
        }
    }

    static final class Compiler {
        private final ClusterSpec model;
        private final int maxRetainedWidth;
        private final Map<Mask, Mask> masks = new HashMap<>();
        private final Map<ProfileKey, Profile> profiles = new LinkedHashMap<>();
        private long[] scratch;
        private long payload, peak;
        private String disabledReason = "";
        private boolean allAllowed = true;

        Compiler(ClusterSpec model, int maxRetainedWidth) {
            if (maxRetainedWidth <= 0) {
                throw new IllegalArgumentException("Maximum retained width must be positive");
            }
            this.model = model;
            this.maxRetainedWidth = maxRetainedWidth;
            try {
                final long words = ((long) model.hostCount + 64) / 64;
                reserve(words, Long.BYTES);
                scratch = new long[Math.toIntExact(words)];
                // Reserve the runtime vectors before any mask is retained.
                reserve(maxRetainedWidth, Integer.BYTES);
                payload -= (long) maxRetainedWidth * Integer.BYTES;
            } catch (Limit limit) {
                disable("workspace_limit");
            }
        }

        void add(Request request, FitCountCache.Sample family, int ordinal) {
            if (!disabledReason.isEmpty()) {
                return;
            }
            try {
                boolean unrestricted = true;
                int pinnedHost = 0;
                boolean conflictingPins = false;
                for (int member = 0; member < request.size(); member++) {
                    final int rules = model.constraints[request.resource(member)];
                    final int pin = model.rules.pinnedHost[rules];
                    unrestricted &= model.rules.candidates[rules] == 0 && pin == 0;
                    if (pin != 0) {
                        conflictingPins |= pinnedHost != 0 && pinnedHost != pin;
                        pinnedHost = pin;
                    }
                }
                final Mask mask;
                if (unrestricted) {
                    mask = Mask.ALL;
                } else if (conflictingPins) {
                    mask = Mask.EMPTY;
                } else {
                    Arrays.fill(scratch, 0);
                    int allowed = 0;
                    final int firstHost = pinnedHost == 0 ? 1 : pinnedHost;
                    final int lastHost = pinnedHost == 0 ? model.hostCount : pinnedHost;
                    for (int host = firstHost; host <= lastHost; host++) {
                        boolean permits = true;
                        for (int member = 0; member < request.size(); member++) {
                            final int rules = model.constraints[request.resource(member)];
                            if (!model.candidates.allows(model.rules.candidates[rules], host)) {
                                permits = false;
                                break;
                            }
                        }
                        if (permits) {
                            scratch[host >>> 6] |= 1L << host;
                            allowed++;
                        }
                    }
                    if (allowed == model.hostCount) {
                        mask = Mask.ALL;
                    } else if (allowed == 0) {
                        mask = Mask.EMPTY;
                    } else {
                        final var found = masks.get(new Mask(scratch));
                        if (found == null) {
                            reserve(scratch.length, Long.BYTES);
                            mask = new Mask(scratch.clone());
                        } else {
                            mask = found;
                        }
                    }
                }
                masks.putIfAbsent(mask, mask);
                allAllowed &= mask == Mask.ALL;
                final var key = new ProfileKey(family, mask);
                final var existing = profiles.get(key);
                if (existing == null) {
                    if (profiles.size() == PROFILE_LIMIT) {
                        disable("profile_limit");
                        return;
                    }
                    profiles.put(key, new Profile(family, mask, profiles.size(), ordinal));
                } else {
                    existing.frequency++;
                }
            } catch (Limit limit) {
                disable("workspace_limit");
            }
        }

        MaskedFitCountOrder finish(FitCountCache cache, int cap) {
            if (!disabledReason.isEmpty()) {
                return null;
            }
            try {
                final var result = new MaskedFitCountOrder(cache, this, cap);
                scratch = null;
                profiles.clear();
                masks.clear();
                return result;
            } catch (Limit limit) {
                disable("workspace_limit");
                return null;
            }
        }

        boolean allAllowed() {
            return allAllowed;
        }

        String disabledReason() {
            return disabledReason;
        }

        long peak() {
            return peak;
        }

        private void reserve(long elements, int width) {
            final long next;
            try {
                next = Math.addExact(payload, Math.multiplyExact(elements, width));
            } catch (ArithmeticException overflow) {
                throw new Limit();
            }
            if (next > WORKSPACE_LIMIT) {
                throw new Limit();
            }
            payload = next;
            peak = Math.max(peak, next);
        }

        private void disable(String reason) {
            disabledReason = reason;
            scratch = null;
            profiles.clear();
            masks.clear();
            payload = 0;
        }
    }

    private record ProfileKey(FitCountCache.Sample family, Mask mask) {
    }

    private static final class Mask {
        static final Mask ALL = new Mask(null);
        static final Mask EMPTY = new Mask(new long[0]);
        private final long[] words;
        private final int hash;

        Mask(long[] words) {
            this.words = words;
            hash = Arrays.hashCode(words);
        }

        boolean allows(int host) {
            return this == ALL || this != EMPTY && (words[host >>> 6] & 1L << host) != 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Mask mask && Arrays.equals(words, mask.words);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Limit extends RuntimeException {
    }
}
