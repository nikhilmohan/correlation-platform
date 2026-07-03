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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SOLE writer to the Pattern Store — every JPA save routes through here (or the
 * consolidation-aware {@code PatternConsolidationService} that delegates its row builds to these
 * helpers). Provides the primitive write operations the consolidation fold composes into ONE DB
 * transaction: the {@code processed_event} idempotency gate, a fresh draft row, the ordered
 * sequence elements, the (union of) supporting instances, and the lifecycle-transition audit row.
 *
 * <p>[ANCHOR-CONSOL] identity is decided by {@link PatternConsolidationService}: anchored patterns
 * upsert-and-aggregate on the anchor identity, unexplained patterns keep the per-event identity.
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

    /** Record only the {@code eventId} for a duplicate/skip (no pattern write). */
    @Transactional
    public void recordProcessed(String eventId, String source) {
        UUID id = UUID.fromString(eventId);
        if (!processedEventRepository.existsByEventId(id)) {
            processedEventRepository.save(
                    new ProcessedEventEntity(id, source, OffsetDateTime.now(), null));
        }
    }

    /** Record the {@code eventId} against a persisted pattern (dedupe set + audit link). */
    void recordProcessedFor(String eventId, String source, UUID patternId, OffsetDateTime at) {
        processedEventRepository.save(
                new ProcessedEventEntity(UUID.fromString(eventId), source, at, patternId));
    }

    /**
     * Build (or reuse) the pattern entity for {@code patternId} and populate the create-time fields
     * from the enriched pattern (used for a NEW row; aggregation of an existing row is done in
     * {@link PatternConsolidationService}). Writes the ordered sequence, supporting instances, and a
     * {@code draft} lifecycle-transition audit row. Does NOT write {@code processed_event} (the
     * consolidation composes that into the same transaction).
     */
    PatternEntity createDraftRow(UUID patternId, EnrichedPattern enriched, double representativeWeight,
            OffsetDateTime now) {
        PatternEntity entity = new PatternEntity();
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
        entity.setAnchorScenarioId(enriched.anchorScenarioId());
        entity.setSnapshotId(enriched.snapshotId());
        entity.setCodebookVersion(enriched.codebookVersion());
        entity.setRepresentativeWeight(representativeWeight);
        entity.setLifecycle("draft");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        replaceSequence(entity, enriched.sequence());
        addSupportingInstances(entity, enriched.supportingInstances());

        // Single save (cascades sequence + supporting instances exactly once).
        patternRepository.save(entity);
        transitionRepository.save(new LifecycleTransitionEntity(
                UUID.randomUUID(), patternId, "-", "draft", null, "pattern discovered", now));
        return entity;
    }

    /** Replace the ordered sequence elements on {@code entity} with {@code seq}. */
    void replaceSequence(PatternEntity entity, List<String> seq) {
        entity.getSequenceElements().clear();
        for (int i = 0; i < seq.size(); i++) {
            entity.getSequenceElements().add(
                    new SequenceElementEntity(UUID.randomUUID(), entity, i, seq.get(i), false));
        }
    }

    /** Append supporting instances not already present (dedup on {@code sourceWindowId}). */
    void addSupportingInstances(PatternEntity entity, List<SupportingInstance> instances) {
        Set<String> existing = new LinkedHashSet<>();
        for (SupportingInstanceEntity e : entity.getSupportingInstances()) {
            existing.add(String.valueOf(e.getSourceWindowId()));
        }
        for (SupportingInstance si : instances) {
            if (!existing.add(String.valueOf(si.sourceWindowId()))) {
                continue; // already present — union semantics
            }
            String occ = si.occurrence() != null ? si.occurrence().toString() : null;
            entity.getSupportingInstances().add(new SupportingInstanceEntity(
                    UUID.randomUUID(), entity, si.sourceWindowId(), si.snapshotId(), occ));
        }
    }

    PatternEntity save(PatternEntity entity) {
        return patternRepository.save(entity);
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize JSON column", e);
        }
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}
