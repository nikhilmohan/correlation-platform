package com.acp.correlationengine.generalize;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.pattern.PatternStore;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the compatibility index lifecycle (spec Task 1 / 1a / 1b): builds and refreshes the
 * {@link CompatibilityIndex} from the approved-pattern set ({@link PatternStore}) and the trail
 * catalog ({@link TrailBuilderClient}), applying the hostability-subset rule
 * ({@link CompatibilityEvaluator}) over each pattern's resolved required object types
 * ({@link RequiredObjectTypesResolver}).
 *
 * <ul>
 *   <li><b>{@code rebuildAll}</b> (startup / {@code trails.built}): re-enumerate the snapshot's
 *       trails, fetch each trail's member types once, resolve every approved pattern's required
 *       types, evaluate compatibility, build a FRESH index, and reference-swap it atomically so
 *       per-alarm lookups never see a half-built index.</li>
 *   <li><b>{@code rebuildForPattern}</b> ({@code patterns.approved}): compute just the one pattern's
 *       compatible-trail set against the cached trail catalog and update the live index for that
 *       pattern only — before the triggering event is acked, so an approved pattern is never
 *       matchable before its compatible set exists.</li>
 * </ul>
 *
 * <p>Fetch resilience (AC41): a trail whose members cannot be fetched is omitted from the catalog and
 * therefore absent from every pattern's compatible set (no corruption, no false positives). A total
 * enumerate failure aborts the rebuild WITHOUT swapping, retaining the last-good index.
 */
public class CompatibilityIndexService {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityIndexService.class);

    private final PatternStore patternStore;
    private final TrailBuilderClient trailBuilder;
    private final RequiredObjectTypesResolver requiredTypesResolver;
    private final CompatibilityEvaluator evaluator;
    private final CorrelationMetrics metrics;
    private final String domain;

    /** The live index, atomically reference-swapped on a full rebuild. Never null after construction. */
    private final AtomicReference<CompatibilityIndex> index;
    /** Cached member types per trail from the latest snapshot, reused by {@code rebuildForPattern}. */
    private volatile Map<String, Set<String>> trailMemberTypeCache = Map.of();
    /** The latest snapshot id observed (from a {@code trails.built} event); null until first seen. */
    private volatile String currentSnapshotId;
    private volatile boolean builtAtLeastOnce = false;

    public CompatibilityIndexService(PatternStore patternStore, TrailBuilderClient trailBuilder,
            RequiredObjectTypesResolver requiredTypesResolver, CompatibilityEvaluator evaluator,
            CorrelationMetrics metrics, String domain) {
        this.patternStore = patternStore;
        this.trailBuilder = trailBuilder;
        this.requiredTypesResolver = requiredTypesResolver;
        this.evaluator = evaluator;
        this.metrics = metrics;
        this.domain = domain;
        this.index = new AtomicReference<>(new CompatibilityIndex(patternStore));
    }

    /** @return the live index (for per-alarm fan-out). Always fully built (or empty). */
    public CompatibilityIndex current() {
        return index.get();
    }

    /** @return the compatible patterns for {@code trailId} — the per-alarm fan-out lookup (AC40). */
    public List<PatternRef> patternsCompatibleWith(String trailId) {
        return index.get().patternsCompatibleWith(trailId);
    }

    public boolean isBuiltAtLeastOnce() {
        return builtAtLeastOnce;
    }

    /**
     * Full rebuild against the given snapshot. Enumerate + fetch member types for every trail, then
     * compute each approved pattern's compatible set, build a fresh index, reference-swap.
     *
     * @param snapshotId the topology snapshot to enumerate against (from {@code trails.built} or the
     *     last-known snapshot at startup); if null, no enumeration is possible and the current index
     *     is retained.
     */
    public synchronized void rebuildAll(String snapshotId, String eventDomain) {
        String snap = snapshotId != null ? snapshotId : currentSnapshotId;
        String dom = eventDomain != null && !eventDomain.isBlank() ? eventDomain : domain;
        if (snap == null) {
            log.info("Compatibility index rebuildAll skipped — no snapshot known yet");
            return;
        }
        List<String> trailIds;
        try {
            trailIds = trailBuilder.listTrailIds(snap, dom);
        } catch (RuntimeException e) {
            log.warn("Trail enumeration failed for snapshot {} — retaining last-good index", snap, e);
            return; // no swap; keep the last-good index (graceful degradation)
        }
        // Fetch member types once per trail; a failing trail is simply omitted (AC41).
        Map<String, Set<String>> memberTypes = new HashMap<>();
        for (String trailId : trailIds) {
            Optional<Set<String>> types = trailBuilder.getTrailMemberTypes(trailId);
            types.ifPresent(t -> memberTypes.put(trailId, t));
        }

        CompatibilityIndex fresh = new CompatibilityIndex(patternStore);
        for (PatternRef pattern : patternStore.all()) {
            Optional<RequiredTypes> req = requiredTypesResolver.resolve(pattern);
            if (req.isEmpty()) {
                metrics.incrementRequiredTypesUnresolved();
                continue; // fail-safe exclusion
            }
            Set<String> compatible = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> e : memberTypes.entrySet()) {
                if (evaluator.isCompatible(req.get(), e.getValue())) {
                    compatible.add(e.getKey());
                }
            }
            fresh.put(pattern.patternId(), compatible);
            metrics.setCompatibleTrailsForPattern(pattern.patternId(), compatible.size());
        }

        this.trailMemberTypeCache = memberTypes;
        this.currentSnapshotId = snap;
        this.index.set(fresh); // atomic reference-swap — lookups see old-or-new, never partial
        this.builtAtLeastOnce = true;
        metrics.incrementIndexRefresh("trails-built-or-startup");
        log.info("Compatibility index rebuilt: snapshot={} trails={} patterns={}",
                snap, memberTypes.size(), fresh.patternIds().size());
    }

    /**
     * Update the live index for a single pattern (the {@code patterns.approved} path). Computes the
     * pattern's compatible-trail set against the cached trail catalog and writes it — before the
     * event is acked — so the pattern is not matchable until its set exists.
     */
    public synchronized void rebuildForPattern(String patternId) {
        PatternRef pattern = patternStore.findById(patternId);
        if (pattern == null) {
            return; // pattern no longer approved — nothing to place
        }
        Optional<RequiredTypes> req = requiredTypesResolver.resolve(pattern);
        Set<String> compatible = new LinkedHashSet<>();
        if (req.isEmpty()) {
            metrics.incrementRequiredTypesUnresolved();
        } else {
            for (Map.Entry<String, Set<String>> e : trailMemberTypeCache.entrySet()) {
                if (evaluator.isCompatible(req.get(), e.getValue())) {
                    compatible.add(e.getKey());
                }
            }
        }
        index.get().replacePattern(patternId, compatible);
        metrics.setCompatibleTrailsForPattern(patternId, compatible.size());
        metrics.incrementIndexRefresh("patterns-approved");
        log.info("Compatibility index updated for pattern {}: compatibleTrails={}",
                patternId, compatible.size());
    }

    /**
     * Refresh every currently approved pattern's placement against the cached catalog (the
     * {@code patterns.approved} path when the whole approved set was re-fetched). Applies
     * {@link #rebuildForPattern} for each held pattern and drops placements for patterns no longer
     * approved.
     */
    public synchronized void rebuildForApprovedSet() {
        CompatibilityIndex live = index.get();
        // Drop patterns that are no longer approved.
        for (String heldPatternId : live.patternIds()) {
            if (patternStore.findById(heldPatternId) == null) {
                live.replacePattern(heldPatternId, Set.of());
            }
        }
        for (PatternRef pattern : patternStore.all()) {
            rebuildForPattern(pattern.patternId());
        }
    }

    /** Remember the snapshot id from a {@code trails.built} event (used by a startup rebuild). */
    public void noteSnapshot(String snapshotId) {
        if (snapshotId != null) {
            this.currentSnapshotId = snapshotId;
        }
    }

    public String currentSnapshotId() {
        return currentSnapshotId;
    }
}
