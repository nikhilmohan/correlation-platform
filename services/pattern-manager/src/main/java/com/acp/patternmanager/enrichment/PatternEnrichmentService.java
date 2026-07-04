package com.acp.patternmanager.enrichment;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.KnowledgeClient;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.SessionWindowDeriver;
import com.acp.patternmanager.rca.RcaResult;
import com.acp.patternmanager.rca.RcaService;
import com.acp.patternmanager.reconcile.CodebookMatch;
import com.acp.patternmanager.reconcile.ReconciliationService;
import com.acp.patternmanager.store.ConsolidationOutcome;
import com.acp.patternmanager.store.PatternConsolidationService;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.acp.patternmanager.structural.StructuralResult;
import com.acp.patternmanager.structural.StructuralValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the enrichment pipeline for one mined pattern (design "PatternEnrichmentService"):
 * read Knowledge params -> reconcile against codebook -> RCA (graph ordering + codebook override,
 * returning the resolved objects) -> structural validation (REUSING those objects) -> derive the
 * session window (pure over timing) -> assemble XAI -> persist draft -> emit patterns.discovered.
 *
 * <p>The Topology resolution is computed once (in RCA) and threaded into structural validation.
 * Session-window derivation calls NO collaborator. Persistence + the eventId record happen in one
 * DB transaction (via {@link PatternStoreService}); the emit follows.
 */
@Service
public class PatternEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(PatternEnrichmentService.class);
    private static final String DEFAULT_DOMAIN = "core-ip";

    private final KnowledgeClient knowledgeClient;
    private final RcaService rcaService;
    private final StructuralValidationService structuralValidationService;
    private final ReconciliationService reconciliationService;
    private final SessionWindowDeriver sessionWindowDeriver;
    private final ExplainabilityAssembler explainabilityAssembler;
    private final PatternConsolidationService consolidationService;
    private final PatternRepository patternRepository;
    private final com.acp.patternmanager.event.PatternEventPublisher eventPublisher;

    public PatternEnrichmentService(KnowledgeClient knowledgeClient, RcaService rcaService,
            StructuralValidationService structuralValidationService,
            ReconciliationService reconciliationService, SessionWindowDeriver sessionWindowDeriver,
            ExplainabilityAssembler explainabilityAssembler,
            PatternConsolidationService consolidationService, PatternRepository patternRepository,
            com.acp.patternmanager.event.PatternEventPublisher eventPublisher) {
        this.knowledgeClient = knowledgeClient;
        this.rcaService = rcaService;
        this.structuralValidationService = structuralValidationService;
        this.reconciliationService = reconciliationService;
        this.sessionWindowDeriver = sessionWindowDeriver;
        this.explainabilityAssembler = explainabilityAssembler;
        this.consolidationService = consolidationService;
        this.patternRepository = patternRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Enrich, persist, and emit for one mined pattern.
     *
     * @param mined the mined-pattern view (sequence, metrics, trailId, timing, provenance)
     * @param eventId the consumed event id (idempotency dedupe key)
     * @param source the consumed event source
     * @param traceId the trace id to propagate onto the discovered event
     */
    public void enrichAndPersist(MinedPatternView mined, String eventId, String source, String traceId) {
        EnrichmentParams params = knowledgeClient.fetchEnrichmentParams();

        // Reconcile first so RCA can apply the codebook override authoritatively.
        CodebookMatch match = reconciliationService.reconcile(mined.sequence(), mined.trailId(), params);

        RcaResult rca = rcaService.analyze(mined.sequence(), params,
                match.matched() ? Optional.of(match) : Optional.empty());

        StructuralResult structural = structuralValidationService.validate(
                rca.resolvedObjects(), rca.rootCauseObjectId(), params);

        DerivedSessionWindow window = sessionWindowDeriver.derive(mined.timing());

        XaiMetadata xai = explainabilityAssembler.assemble(mined.support(), mined.confidence(),
                mined.lift(), mined.timing(), rca, structural, window, mined.supportingInstances());

        // [SAMPLE-ALARMS AC-SA-8] Reconcile anchorScenarioId -> codebookMatchId: when the standard
        // codebook-override found no match but the mined provenance carries a populated
        // anchorScenarioId, propagate it into codebookMatchId so the UI's fault-origin display is
        // consistent. Internal to the existing enrichment pipeline; no contract change.
        String codebookMatchId = xai.codebookMatchId();
        if ((codebookMatchId == null || codebookMatchId.isBlank())
                && mined.anchorScenarioId() != null && !mined.anchorScenarioId().isBlank()) {
            codebookMatchId = mined.anchorScenarioId();
            log.info("propagated anchorScenarioId={} to codebookMatchId (no codebook match)",
                    mined.anchorScenarioId());
        }

        EnrichedPattern enriched = new EnrichedPattern(
                mined.trailId(),
                mined.sequence(),
                rca.rootCauseAlarmType(),
                xai.support(),
                xai.confidence(),
                xai.lift(),
                xai.timing(),
                xai.sessionWindow(),
                codebookMatchId,
                xai.reconcileStatus(),
                xai.structurallyValidated(),
                xai.structuralValidationReason(),
                xai.instanceCount(),
                xai.supportingInstances(),
                mined.sampleAlarms(),
                mined.domain() != null ? mined.domain() : DEFAULT_DOMAIN,
                mined.snapshotId(),
                mined.codebookVersion(),
                mined.anchorScenarioId(),
                mined.sourceWindowId());

        // [ANCHOR-CONSOL] consolidate by anchor identity (or per-event identity for unexplained).
        // The whole upsert-and-aggregate + processed_event write is one DB transaction.
        ConsolidationOutcome outcome = consolidationService.consolidate(enriched, eventId, source);

        // Emit-once-per-identity: only the CREATING contributor emits a PatternDiscoveredEvent; a
        // later sub-run folding into an existing anchored row (or a replay no-op) emits nothing.
        if (outcome.created()) {
            PatternEntity persisted = patternRepository.findById(outcome.patternId())
                    .orElseThrow(() -> new IllegalStateException(
                            "pattern vanished after persist: " + outcome.patternId()));
            eventPublisher.publishDiscovered(persisted, traceId);
        } else {
            log.info("no discovered event emitted for patternId={} (created={}, folded={})",
                    outcome.patternId(), outcome.created(), outcome.folded());
        }
    }

    /**
     * A normalized view of a mined pattern extracted from the consumed event. Kept separate from the
     * event-model POJO so provenance/timing are already unwrapped for enrichment.
     */
    public record MinedPatternView(
            List<String> sequence,
            double support,
            double confidence,
            double lift,
            String trailId,
            Map<String, Object> timing,
            String domain,
            String snapshotId,
            String codebookVersion,
            String anchorScenarioId,
            String sourceWindowId,
            List<SupportingInstance> supportingInstances,
            List<SampleAlarm> sampleAlarms) {

        /** Build a view from a raw envelope-payload JsonNode (post schema validation). */
        public static MinedPatternView from(JsonNode payload,
                com.fasterxml.jackson.databind.ObjectMapper mapper) {
            List<String> seq = new ArrayList<>();
            payload.path("sequence").forEach(n -> seq.add(n.asText()));

            Map<String, Object> timing = mapper.convertValue(payload.path("timing"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            JsonNode prov = payload.path("provenance");
            String domain = text(prov, "domain");
            String sourceWindowId = text(prov, "sourceWindowId");
            String snapshotId = text(prov, "snapshotId");
            // [ANCHOR-CONSOL] anchorScenarioId + codebookVersion already on the FROZEN provenance
            // (PR #331). null/absent anchorScenarioId => unexplained (never consolidated by anchor).
            String codebookVersion = text(prov, "codebookVersion");
            String anchorScenarioId = text(prov, "anchorScenarioId");

            List<SupportingInstance> instances = new ArrayList<>();
            // The mined event's provenance is a single window reference (no occurrence list in the
            // frozen schema); surface it as one supporting instance so instanceCount > 0.
            if (sourceWindowId != null || snapshotId != null) {
                instances.add(new SupportingInstance(sourceWindowId, snapshotId, prov));
            }

            // [SAMPLE-ALARMS] Best-effort parse of the optional frozen sampleAlarms[] (DA-4); absent /
            // null / malformed -> empty (backward-compat, pattern still persists).
            List<SampleAlarm> sampleAlarms = SampleAlarm.parse(payload);

            return new MinedPatternView(
                    seq,
                    payload.path("support").asDouble(),
                    payload.path("confidence").asDouble(),
                    payload.path("lift").asDouble(),
                    payload.path("trailId").asText(),
                    timing != null ? timing : Map.of(),
                    domain,
                    snapshotId,
                    codebookVersion,
                    anchorScenarioId,
                    sourceWindowId,
                    instances,
                    sampleAlarms);
        }

        /** @return the trimmed text at {@code field}, or null when absent/blank/JSON-null. */
        private static String text(JsonNode node, String field) {
            JsonNode v = node.path(field);
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            String s = v.asText(null);
            return (s == null || s.isBlank()) ? null : s;
        }
    }
}
