package com.acp.patternmanager.store;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.SessionWindowDeriver;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.store.entity.ContributingEventEntity;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.ContributingEventRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ANCHOR-CONSOL] Decides the Pattern Store IDENTITY for an enriched mined event and folds it into
 * the store — the P2 over-count fix. All writes route through {@link PatternStoreService} (the sole
 * writer); this is the identity + aggregation policy in front of it.
 *
 * <ul>
 *   <li><b>Anchored</b> ({@code anchorScenarioId != null}) — {@code patternId} is a deterministic
 *       UUIDv5 over the anchor identity {@code (domain, snapshotId, codebookVersion,
 *       anchorScenarioId)}, so ALL mined events for one fault-origin (across sub-runs) map to one
 *       row. The row is loaded {@code SELECT ... FOR UPDATE} (serializing concurrent folds); a
 *       {@code contributing_event INSERT ... ON CONFLICT (event_id) DO NOTHING} guards the fold so
 *       each {@code eventId} contributes at most once (replay-safe). First contributor CREATES the
 *       draft and emits ONE discovered event; each later distinct contributor AGGREGATES
 *       (sum occurrences, occurrence-weighted-mean support/confidence/lift, union supporting
 *       instances, combine timing + recompute {@code sessionWindow}, keep the representative
 *       sequence) and emits nothing.
 *   <li><b>Unexplained</b> ({@code anchorScenarioId == null/absent}) — [SIG-FOLD] cascade-signature
 *       UUIDv5 over {@code (sequence, domain, snapshotId)} ({@code trailId} + {@code sourceWindowId}
 *       dropped). The SAME cascade shape — from any trail, any mining window — FOLDS into ONE row,
 *       mirroring the anchored path (row lock + {@code contributing_event} guard + aggregate),
 *       accumulating {@code occurrenceCount} / {@code trailCount} / {@code instanceCount} /
 *       {@code lastSeen}. The first occurrence CREATES + emits ONE discovered event; later
 *       occurrences fold and emit nothing.
 * </ul>
 *
 * <p>The whole fold (row lock + {@code contributing_event} insert + aggregate + {@code
 * processed_event} insert) runs in ONE DB transaction — a mid-fold failure rolls back, the offset is
 * not committed, and the redelivered event re-folds safely (the guard makes the retry idempotent).
 */
@Service
public class PatternConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(PatternConsolidationService.class);

    private final PatternRepository patternRepository;
    private final ContributingEventRepository contributingEventRepository;
    private final PatternStoreService storeService;
    private final SessionWindowDeriver sessionWindowDeriver;

    public PatternConsolidationService(PatternRepository patternRepository,
            ContributingEventRepository contributingEventRepository,
            PatternStoreService storeService, SessionWindowDeriver sessionWindowDeriver) {
        this.patternRepository = patternRepository;
        this.contributingEventRepository = contributingEventRepository;
        this.storeService = storeService;
        this.sessionWindowDeriver = sessionWindowDeriver;
    }

    /**
     * Consolidate one enriched mined event into the Pattern Store, atomically.
     *
     * @param enriched the enriched pattern (carries anchor identity + metrics + timing)
     * @param eventId the consumed event id (dedupe / fold-guard key)
     * @param source the consumed event source
     * @return the outcome: which {@code patternId} it mapped to and whether the row was CREATED
     *     (emit a discovered event) or folded/no-op (emit nothing)
     */
    @Transactional
    public ConsolidationOutcome consolidate(EnrichedPattern enriched, String eventId, String source) {
        return enriched.isAnchored()
                ? consolidateAnchored(enriched, eventId, source)
                : persistUnexplained(enriched, eventId, source);
    }

    private ConsolidationOutcome consolidateAnchored(EnrichedPattern enriched, String eventId,
            String source) {
        UUID patternId = UuidV5.anchorIdentity(enriched.domain(), enriched.snapshotId(),
                enriched.codebookVersion(), enriched.anchorScenarioId());
        OffsetDateTime now = OffsetDateTime.now();
        int occ = Math.max(1, enriched.instanceCount());

        // Serialize concurrent folds of this anchor identity on the row lock (AC-C8).
        Optional<PatternEntity> existing = patternRepository.findByIdForUpdate(patternId);

        if (existing.isEmpty()) {
            PatternEntity created = storeService.createDraftRow(
                    patternId, enriched, enriched.support() * occ, now);
            recordContributor(eventId, patternId, enriched, occ, source, now);
            // [SIG-FOLD] record the creating contributor's trail (trailCount initialised to 1).
            storeService.recordTrail(patternId, enriched.trailId(), now);
            log.info("pattern_consolidated action=create patternId={} anchorScenarioId={} instanceCount={}",
                    patternId, enriched.anchorScenarioId(), created.getInstanceCount());
            return ConsolidationOutcome.created(patternId);
        }

        // Fold guard: INSERT ... ON CONFLICT (event_id) DO NOTHING. 0 rows => already folded (replay).
        int inserted = contributingEventRepository.insertIgnoreConflict(
                UUID.fromString(eventId), patternId, enriched.anchorScenarioId(), occ,
                enriched.support(), now);
        if (inserted == 0) {
            log.info("anchor fold no-op (replay) patternId={} eventId={}", patternId, eventId);
            storeService.recordProcessedFor(eventId, source, patternId, now);
            return ConsolidationOutcome.noop(patternId);
        }

        aggregate(existing.get(), enriched, occ, now);
        storeService.recordProcessedFor(eventId, source, patternId, now);
        log.info("pattern_consolidated action=fold patternId={} anchorScenarioId={} contributors={} "
                        + "instanceCount={} occurrenceCount={} trailCount={} lastSeen={}",
                patternId, enriched.anchorScenarioId(),
                contributingEventRepository.countByPatternId(patternId), existing.get().getInstanceCount(),
                existing.get().getOccurrenceCount(), existing.get().getTrailCount(),
                existing.get().getLastSeen());
        return ConsolidationOutcome.folded(patternId);
    }

    /**
     * [SIG-FOLD] The single shared fold — used by BOTH the anchored and the unexplained (signature)
     * paths. Adds the impact-metric maintenance (occurrenceCount, trailCount, firstSeen/lastSeen) on
     * top of the existing support/confidence/lift/timing/instanceCount math, which is UNCHANGED (the
     * anchored AC-C1..C8 semantics are preserved — the additions are orthogonal, AC-SF-11).
     */
    private void aggregate(PatternEntity row, EnrichedPattern e, int occ, OffsetDateTime eventTs) {
        // Weighting stays the member-alarm counts (unchanged anchored semantics).
        int oldInstance = row.getInstanceCount();

        // Ratios -> occurrence-weighted (member-alarm-weighted) mean.
        row.setSupport(PatternAggregator.weightedMean(row.getSupport(), oldInstance, e.support(), occ));
        row.setConfidence(PatternAggregator.weightedMean(row.getConfidence(), oldInstance, e.confidence(), occ));
        row.setLift(PatternAggregator.weightedMean(row.getLift(), oldInstance, e.lift(), occ));

        Map<String, Object> combinedTiming = PatternAggregator.combineTiming(
                readTiming(row), oldInstance, e.timing(), occ);
        row.setTimingJson(storeService.writeJson(combinedTiming));

        // Recompute the session window from the COMBINED timing (deterministic function of it).
        DerivedSessionWindow window = sessionWindowDeriver.derive(combinedTiming);
        row.setSessionWindowMs(window.windowMs());
        row.setSessionWindowType(window.type().wire());

        // instanceCount = total member-alarm volume (sum of support counts). Unchanged.
        row.setInstanceCount(oldInstance + occ);

        // [SIG-FOLD] occurrenceCount counts EVENTS (distinct folded eventIds), not alarms -> +1 per
        // genuine fold; a pure event tally orthogonal to the weighting above.
        row.setOccurrenceCount(row.getOccurrenceCount() + 1);

        // [SIG-FOLD] record the contributing trail (distinct set); bump trail_count by rows inserted
        // (0 or 1). A NEW eventId on an already-seen trail bumps occurrenceCount but NOT trailCount.
        int trailsAdded = storeService.recordTrail(row.getPatternId(), e.trailId(), eventTs);
        row.setTrailCount(row.getTrailCount() + trailsAdded);

        // [SIG-FOLD] firstSeen unchanged (earliest occurrence); lastSeen bumped to the latest.
        if (row.getLastSeen() == null || eventTs.isAfter(row.getLastSeen())) {
            row.setLastSeen(eventTs);
        }

        // Representative sequence: highest occurrence-weighted support, tie longest then lexicographic.
        // (For a signature fold the sequence is byte-identical across contributors, so this never
        // replaces — the bookkeeping runs for parity and is a no-op replace.)
        double newWeight = e.support() * occ;
        double currentWeight = row.getRepresentativeWeight() != null ? row.getRepresentativeWeight() : 0.0;
        List<String> currentSeq = row.getSequenceElements().stream()
                .map(com.acp.patternmanager.store.entity.SequenceElementEntity::getAlarmType)
                .toList();
        if (PatternAggregator.shouldReplaceRepresentative(newWeight, e.sequence(), currentWeight, currentSeq)) {
            storeService.replaceSequence(row, e.sequence());
            row.setRepresentativeWeight(newWeight);
            row.setRootCauseAlarmType(e.rootCauseAlarmType());
        }

        // Union supporting instances (dedup on sourceWindowId).
        storeService.addSupportingInstances(row, e.supportingInstances());

        // [SAMPLE-ALARMS DA-1] The fold DELIBERATELY does NOT touch the sample_alarm collection: the
        // pattern keeps ONE bounded sample = the FIRST/creating contributor's (written once in
        // createDraftRow). No clear()/re-add/append here — this keeps the sample deterministic +
        // bounded across folds, makes it idempotent/replay-safe, and sidesteps the sequence_element
        // INSERT-before-DELETE dup-key trap (#342) entirely for this collection.

        row.setUpdatedAt(OffsetDateTime.now());
        storeService.save(row);
    }

    private void recordContributor(String eventId, UUID patternId, EnrichedPattern e, int occ,
            String source, OffsetDateTime now) {
        // On create the row was just inserted; record its contributing event + processed_event.
        contributingEventRepository.insertIgnoreConflict(
                UUID.fromString(eventId), patternId, e.anchorScenarioId(), occ, e.support(), now);
        storeService.recordProcessedFor(eventId, source, patternId, now);
    }

    /**
     * [SIG-FOLD] Unexplained patterns fold CROSS-TRAIL by cascade SIGNATURE — one pattern row per
     * {@code (sequence, domain, snapshotId)}, accumulating occurrence + extent + impact metrics.
     * This MIRRORS {@link #consolidateAnchored}: row-lock -> {@code contributing_event} fold guard ->
     * shared {@link #aggregate}. The old per-occurrence identity (trailId + sourceWindowId in the key)
     * and the no-op-on-existing behaviour are GONE — that was the duplicate-row bug.
     */
    private ConsolidationOutcome persistUnexplained(EnrichedPattern enriched, String eventId,
            String source) {
        UUID patternId = UuidV5.signatureIdentity(enriched.sequence(), enriched.domain(),
                enriched.snapshotId());
        OffsetDateTime now = OffsetDateTime.now();
        int occ = Math.max(1, enriched.instanceCount());
        String signature = String.join(",", enriched.sequence());

        // Serialize concurrent folds of this signature on the row lock (mirrors the anchored path).
        Optional<PatternEntity> existing = patternRepository.findByIdForUpdate(patternId);

        if (existing.isEmpty()) {
            PatternEntity created = storeService.createDraftRow(
                    patternId, enriched, enriched.support() * occ, now);
            recordContributor(eventId, patternId, enriched, occ, source, now);
            storeService.recordTrail(patternId, enriched.trailId(), now);
            log.info("pattern_consolidated action=create patternId={} signature={} domain={} snapshotId={} "
                            + "instanceCount={}",
                    patternId, signature, enriched.domain(), enriched.snapshotId(),
                    created.getInstanceCount());
            return ConsolidationOutcome.created(patternId);
        }

        // Fold guard: INSERT ... ON CONFLICT (event_id) DO NOTHING. 0 rows => replay -> no-op.
        int inserted = contributingEventRepository.insertIgnoreConflict(
                UUID.fromString(eventId), patternId, null, occ, enriched.support(), now);
        if (inserted == 0) {
            log.info("pattern_consolidated action=noop patternId={} signature={} eventId={} (replay)",
                    patternId, signature, eventId);
            storeService.recordProcessedFor(eventId, source, patternId, now);
            return ConsolidationOutcome.noop(patternId);
        }

        PatternEntity row = existing.get();
        aggregate(row, enriched, occ, now);
        storeService.recordProcessedFor(eventId, source, patternId, now);
        log.info("pattern_consolidated action=fold patternId={} signature={} domain={} snapshotId={} "
                        + "occurrenceCount={} trailCount={} instanceCount={} lastSeen={}",
                patternId, signature, enriched.domain(), enriched.snapshotId(),
                row.getOccurrenceCount(), row.getTrailCount(), row.getInstanceCount(), row.getLastSeen());
        return ConsolidationOutcome.folded(patternId);
    }

    private Map<String, Object> readTiming(PatternEntity row) {
        try {
            String json = row.getTimingJson();
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return storeService.objectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read persisted timing JSON for fold", ex);
        }
    }
}
