package com.acp.patternmanager.store;

import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.enrichment.SupportingInstance;
import com.acp.patternmanager.store.entity.LifecycleTransitionEntity;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.ProcessedEventEntity;
import com.acp.patternmanager.store.entity.SequenceElementEntity;
import com.acp.patternmanager.store.entity.SupportingInstanceEntity;
import com.acp.patternmanager.store.repo.LifecycleTransitionRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.acp.patternmanager.store.repo.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SOLE writer to the Pattern Store. Persists an enriched draft pattern (row + sequence elements
 * + supporting instances + a draft lifecycle-transition audit row + the {@code processed_event}
 * dedupe row) atomically in ONE DB transaction — so a redelivered {@code eventId} is a no-op
 * (criterion 10). {@code patternId} is a deterministic UUIDv5 over the mining provenance for upsert
 * idempotency.
 */
@Service
public class PatternStoreService {

    private static final Logger log = LoggerFactory.getLogger(PatternStoreService.class);

    private final PatternRepository patternRepository;
    private final LifecycleTransitionRepository transitionRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PatternStoreService(PatternRepository patternRepository,
            LifecycleTransitionRepository transitionRepository,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.patternRepository = patternRepository;
        this.transitionRepository = transitionRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    /** @return whether this {@code eventId} was already processed (idempotency gate). */
    @Transactional(readOnly = true)
    public boolean alreadyProcessed(String eventId) {
        return processedEventRepository.existsByEventId(UUID.fromString(eventId));
    }

    /**
     * Persist an enriched pattern as {@code draft} and record the {@code eventId} — atomically.
     *
     * @param enriched the fully-enriched pattern
     * @param eventId the consumed event id (idempotency dedupe key)
     * @param source the consumed event source
     * @return the persisted pattern id
     */
    @Transactional
    public UUID persistDraft(EnrichedPattern enriched, String eventId, String source) {
        UUID patternId = deterministicPatternId(enriched);
        OffsetDateTime now = OffsetDateTime.now();

        PatternEntity entity = patternRepository.findById(patternId).orElseGet(PatternEntity::new);
        boolean isNew = entity.getPatternId() == null;

        entity.setPatternId(patternId);
        entity.setTrailId(enriched.trailId());
        entity.setRootCauseAlarmType(enriched.rootCauseAlarmType());
        entity.setSupport(enriched.support());
        entity.setConfidence(enriched.confidence());
        entity.setLift(enriched.lift());
        entity.setTimingJson(writeJson(enriched.timing()));
        entity.setCodebookMatchId(enriched.codebookMatchId());
        entity.setReconcileStatus(enriched.reconcileStatus());
        entity.setStructurallyValidated(enriched.structurallyValidated());
        entity.setStructuralValidationReason(enriched.structuralValidationReason());
        entity.setSessionWindowMs(enriched.sessionWindow().windowMs());
        entity.setSessionWindowType(enriched.sessionWindow().type().wire());
        entity.setInstanceCount(enriched.instanceCount());
        entity.setDomain(enriched.domain());
        if (isNew) {
            entity.setLifecycle("draft");
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);

        // Rebuild ordered sequence elements.
        entity.getSequenceElements().clear();
        List<String> seq = enriched.sequence();
        for (int i = 0; i < seq.size(); i++) {
            entity.getSequenceElements().add(
                    new SequenceElementEntity(UUID.randomUUID(), entity, i, seq.get(i), false));
        }
        // Rebuild supporting instances.
        entity.getSupportingInstances().clear();
        for (SupportingInstance si : enriched.supportingInstances()) {
            String occ = si.occurrence() != null ? si.occurrence().toString() : null;
            entity.getSupportingInstances().add(new SupportingInstanceEntity(
                    UUID.randomUUID(), entity, si.sourceWindowId(), si.snapshotId(), occ));
        }

        patternRepository.save(entity);

        if (isNew) {
            transitionRepository.save(new LifecycleTransitionEntity(
                    UUID.randomUUID(), patternId, "-", "draft", null, "pattern discovered", now));
        }

        processedEventRepository.save(new ProcessedEventEntity(
                UUID.fromString(eventId), source, now, patternId));

        log.info("persisted draft pattern {} (new={}, lifecycle={})", patternId, isNew,
                entity.getLifecycle());
        return patternId;
    }

    /** Record only the {@code eventId} for a duplicate/skip (no pattern write). */
    @Transactional
    public void recordProcessed(String eventId, String source) {
        UUID id = UUID.fromString(eventId);
        if (!processedEventRepository.existsByEventId(id)) {
            processedEventRepository.save(
                    new ProcessedEventEntity(id, source, OffsetDateTime.now(), null));
        }
    }

    private UUID deterministicPatternId(EnrichedPattern enriched) {
        String name = enriched.trailId() + "|" + String.join(",", enriched.sequence()) + "|"
                + firstSupporting(enriched);
        return UuidV5.from(name);
    }

    private String firstSupporting(EnrichedPattern enriched) {
        if (enriched.supportingInstances().isEmpty()) {
            return "";
        }
        SupportingInstance si = enriched.supportingInstances().get(0);
        return (si.sourceWindowId() != null ? si.sourceWindowId() : "") + "|"
                + (si.snapshotId() != null ? si.snapshotId() : "");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize JSON column", e);
        }
    }
}
