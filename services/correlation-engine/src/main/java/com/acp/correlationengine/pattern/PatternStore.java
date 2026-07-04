package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe reference model of approved patterns, indexed by {@code (trailId, patternId)} and by
 * {@code trailId -> active patternIds} (the fan-out driver). Low-churn reference data; re-derivable
 * from the Pattern Manager read API on restart.
 *
 * <p>The {@code trailId} on each {@link PatternRef} comes from {@code PatternView.trailId}
 * (the read API), never from a {@code patterns.approved} event (AC27).
 */
public class PatternStore {

    /** (trailId :: patternId) -> PatternRef */
    private final Map<String, PatternRef> byKey = new ConcurrentHashMap<>();
    /** trailId -> set of active patternIds */
    private final Map<String, Set<String>> trailIndex = new ConcurrentHashMap<>();
    /** patternId -> PatternRef — the generalization lookup (a pattern is one record, many trails). */
    private final Map<String, PatternRef> byPatternId = new ConcurrentHashMap<>();

    private static String key(String trailId, String patternId) {
        return trailId + "::" + patternId;
    }

    /** Replace the whole approved set (a full re-fetch from the read API is authoritative). */
    public synchronized void replaceAll(List<PatternRef> patterns) {
        byKey.clear();
        trailIndex.clear();
        byPatternId.clear();
        for (PatternRef p : patterns) {
            upsert(p);
        }
    }

    /** Add or refresh a single pattern under its {@code trailId} (from {@code PatternView.trailId}). */
    public synchronized void upsert(PatternRef pattern) {
        byKey.put(key(pattern.trailId(), pattern.patternId()), pattern);
        trailIndex.computeIfAbsent(pattern.trailId(), t -> ConcurrentHashMap.newKeySet())
                .add(pattern.patternId());
        byPatternId.put(pattern.patternId(), pattern);
    }

    /** @return the patterns active on {@code trailId} (empty if none) — the fan-out lookup. */
    public List<PatternRef> activePatternsOn(String trailId) {
        Set<String> ids = trailIndex.getOrDefault(trailId, Set.of());
        return ids.stream()
                .map(id -> byKey.get(key(trailId, id)))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * @return the approved pattern with {@code patternId}, or {@code null} if none is held. Used by
     *     the {@code CompatibilityIndex} to resolve the compatible-pattern ids on a trail back to
     *     their {@link PatternRef} (the pattern generalization fan-out) — the single-owner lookup.
     */
    public PatternRef findById(String patternId) {
        return byPatternId.get(patternId);
    }

    /** @return all approved patterns currently held (compatibility-index full rebuild source). */
    public List<PatternRef> all() {
        return List.copyOf(byPatternId.values());
    }

    /** @return true if any patterns are active on {@code trailId}. */
    public boolean hasPatternsOn(String trailId) {
        Set<String> ids = trailIndex.get(trailId);
        return ids != null && !ids.isEmpty();
    }

    /** @return the total number of approved patterns held (readiness/observability). */
    public int size() {
        return byKey.size();
    }
}
