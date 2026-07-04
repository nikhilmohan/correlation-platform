package com.acp.correlationengine.generalize;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.pattern.PatternStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, thread-safe compatibility index — the per-alarm fan-out driver under pattern
 * generalization (spec Task 3, AC36, AC40). Derived, rebuildable-from-source reference state (no
 * durability): on restart the startup {@code rebuildAll()} reconstructs it from Trail Builder +
 * Pattern Manager.
 *
 * <ul>
 *   <li>{@code trailToPatterns}: {@code trailId -> compatible patternIds} — the bounded O(1) per-alarm
 *       lookup, mirroring {@code PatternStore.trailIndex}.</li>
 *   <li>{@code patternToTrails}: {@code patternId -> compatible trailIds} — per-pattern rebuild on
 *       {@code patterns.approved} and the {@code compatible_trails_per_pattern} gauge source.</li>
 * </ul>
 *
 * <p>A whole-index rebuild builds a fresh instance and the owning service reference-swaps it, so
 * per-alarm lookups never observe a half-built index (bounded transition window). Per-pattern updates
 * mutate only that pattern's entries under a per-pattern lock.
 */
public final class CompatibilityIndex {

    private final Map<String, Set<String>> trailToPatterns = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> patternToTrails = new ConcurrentHashMap<>();
    private final PatternStore patternStore;

    public CompatibilityIndex(PatternStore patternStore) {
        this.patternStore = patternStore;
    }

    /**
     * Record that {@code patternId} is compatible with every trail in {@code compatibleTrailIds}.
     * Used while building a fresh index; not for incremental single-pattern updates on a live index.
     */
    public void put(String patternId, Set<String> compatibleTrailIds) {
        Set<String> trails = ConcurrentHashMap.newKeySet();
        trails.addAll(compatibleTrailIds);
        patternToTrails.put(patternId, trails);
        for (String trailId : compatibleTrailIds) {
            trailToPatterns.computeIfAbsent(trailId, t -> ConcurrentHashMap.newKeySet())
                    .add(patternId);
        }
    }

    /**
     * Replace one pattern's placements on a LIVE index (the {@code patterns.approved} path): remove
     * the pattern from every trail set it currently occupies, then place it on the new compatible
     * set. Other patterns' lookups are unaffected. Synchronized per index instance so a per-pattern
     * update is atomic with respect to another update.
     */
    public synchronized void replacePattern(String patternId, Set<String> compatibleTrailIds) {
        Set<String> previous = patternToTrails.getOrDefault(patternId, Set.of());
        for (String trailId : previous) {
            if (!compatibleTrailIds.contains(trailId)) {
                Set<String> set = trailToPatterns.get(trailId);
                if (set != null) {
                    set.remove(patternId);
                    if (set.isEmpty()) {
                        trailToPatterns.remove(trailId, set);
                    }
                }
            }
        }
        put(patternId, compatibleTrailIds);
    }

    /**
     * @return the {@link PatternRef}s compatible with {@code trailId} — resolved from the pattern ids
     *     placed on the trail against the single-owner {@link PatternStore}. Bounded lookup: one map
     *     get + a set of ids, independent of the total (pattern x trail) index size (AC40).
     */
    public List<PatternRef> patternsCompatibleWith(String trailId) {
        Set<String> ids = trailToPatterns.getOrDefault(trailId, Set.of());
        List<PatternRef> out = new ArrayList<>(ids.size());
        for (String patternId : ids) {
            PatternRef ref = patternStore.findById(patternId);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    /** @return the set of trail ids compatible with {@code patternId} (gauge / rebuild). */
    public Set<String> compatibleTrailIds(String patternId) {
        return Set.copyOf(patternToTrails.getOrDefault(patternId, Set.of()));
    }

    /** @return the number of trails compatible with {@code patternId} (gauge source). */
    public int compatibleTrailCount(String patternId) {
        return patternToTrails.getOrDefault(patternId, Set.of()).size();
    }

    /** @return every patternId currently held in the index. */
    public Set<String> patternIds() {
        return Set.copyOf(patternToTrails.keySet());
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(System.identityHashCode(this));
    }
}
