package com.acp.patternmanager.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.KnowledgeClient;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.SessionWindowDeriver;
import com.acp.patternmanager.enrichment.PatternEnrichmentService.MinedPatternView;
import com.acp.patternmanager.event.PatternEventPublisher;
import com.acp.patternmanager.rca.RcaResult;
import com.acp.patternmanager.rca.RcaService;
import com.acp.patternmanager.reconcile.CodebookMatch;
import com.acp.patternmanager.reconcile.ReconciliationService;
import com.acp.patternmanager.store.ConsolidationOutcome;
import com.acp.patternmanager.store.PatternConsolidationService;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.acp.patternmanager.structural.StructuralResult;
import com.acp.patternmanager.structural.StructuralValidationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AC-SA-8 (related cleanup): when the standard codebook-override finds NO match but the mined
 * provenance carries a populated {@code anchorScenarioId}, the enrichment pipeline propagates it into
 * the persisted {@code codebookMatchId}. Mocks the whole pipeline and captures the
 * {@link EnrichedPattern} handed to consolidation to assert the propagated value.
 */
@ExtendWith(MockitoExtension.class)
class AnchorScenarioReconciliationTest {

    @Mock private KnowledgeClient knowledgeClient;
    @Mock private RcaService rcaService;
    @Mock private StructuralValidationService structuralValidationService;
    @Mock private ReconciliationService reconciliationService;
    @Mock private ExplainabilityAssembler explainabilityAssembler;
    @Mock private PatternConsolidationService consolidationService;
    @Mock private PatternRepository patternRepository;
    @Mock private PatternEventPublisher eventPublisher;

    private PatternEnrichmentService svc;

    @BeforeEach
    void setUp() {
        // Real deriver (pure over timing); everything else mocked.
        SessionWindowDeriver deriver = new SessionWindowDeriver(
                new com.acp.patternmanager.config.SessionWindowProperties(null, null, null, null, null, null));
        svc = new PatternEnrichmentService(knowledgeClient, rcaService, structuralValidationService,
                reconciliationService, deriver, explainabilityAssembler, consolidationService,
                patternRepository, eventPublisher);
    }

    // AC-SA-8: mock Codebook returns no match; provenance.anchorScenarioId = "scenario-42" ->
    // persisted codebookMatchId == "scenario-42".
    @Test
    void propagatesAnchorToCodebookMatchId() {
        EnrichmentParams params = new EnrichmentParams(4, "lenient", "flag", 1.0, 0.5, 0.5);
        when(knowledgeClient.fetchEnrichmentParams()).thenReturn(params);
        // No codebook match found by the standard override.
        when(reconciliationService.reconcile(any(), any(), any())).thenReturn(CodebookMatch.unexplained());
        when(rcaService.analyze(any(), any(), eq(Optional.empty())))
                .thenReturn(new RcaResult("FiberFault", null, "unexplained", List.of(), null));
        when(structuralValidationService.validate(any(), any(), any()))
                .thenReturn(new StructuralResult(true, null));
        // XAI carries a null codebookMatchId (the no-match outcome) — this is what gets overridden.
        when(explainabilityAssembler.assemble(anyDouble(), anyDouble(), anyDouble(), any(), any(),
                any(), any(), any()))
                .thenReturn(new XaiMetadata(1, 0.4, 0.9, 3.0, Map.of("timeframeMs", 9000),
                        null, "unexplained", true, null,
                        new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                        List.of()));

        ArgumentCaptor<EnrichedPattern> captor = ArgumentCaptor.forClass(EnrichedPattern.class);
        UUID pid = UUID.randomUUID();
        lenient().when(consolidationService.consolidate(captor.capture(), anyString(), anyString()))
                .thenReturn(ConsolidationOutcome.noop(pid)); // no emit -> no findById

        MinedPatternView view = new MinedPatternView(
                List.of("FiberFault"), 0.4, 0.9, 3.0, "trail:1", Map.of("timeframeMs", 9000),
                "core-ip", "snap-9", "cb-3", "scenario-42", "sw:1", List.of(), List.of());

        svc.enrichAndPersist(view, UUID.randomUUID().toString(), "pattern-miner", "trace-1");

        EnrichedPattern enriched = captor.getValue();
        assertThat(enriched.codebookMatchId()).isEqualTo("scenario-42");
    }

    // Companion: a real codebook match is NOT overridden by anchorScenarioId (propagation only fills
    // the gap when there is no match).
    @Test
    void doesNotOverrideExistingCodebookMatch() {
        EnrichmentParams params = new EnrichmentParams(4, "lenient", "flag", 1.0, 0.5, 0.5);
        when(knowledgeClient.fetchEnrichmentParams()).thenReturn(params);
        when(reconciliationService.reconcile(any(), any(), any()))
                .thenReturn(new CodebookMatch("cb-scenario-7", "FiberFault", "confirmed"));
        when(rcaService.analyze(any(), any(), any()))
                .thenReturn(new RcaResult("FiberFault", "cb-scenario-7", "confirmed", List.of(), null));
        when(structuralValidationService.validate(any(), any(), any()))
                .thenReturn(new StructuralResult(true, null));
        when(explainabilityAssembler.assemble(anyDouble(), anyDouble(), anyDouble(), any(), any(),
                any(), any(), any()))
                .thenReturn(new XaiMetadata(1, 0.4, 0.9, 3.0, Map.of("timeframeMs", 9000),
                        "cb-scenario-7", "confirmed", true, null,
                        new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                        List.of()));

        ArgumentCaptor<EnrichedPattern> captor = ArgumentCaptor.forClass(EnrichedPattern.class);
        lenient().when(consolidationService.consolidate(captor.capture(), anyString(), anyString()))
                .thenReturn(ConsolidationOutcome.noop(UUID.randomUUID()));

        MinedPatternView view = new MinedPatternView(
                List.of("FiberFault"), 0.4, 0.9, 3.0, "trail:1", Map.of("timeframeMs", 9000),
                "core-ip", "snap-9", "cb-3", "scenario-42", "sw:1", List.of(), List.of());

        svc.enrichAndPersist(view, UUID.randomUUID().toString(), "pattern-miner", "trace-1");

        assertThat(captor.getValue().codebookMatchId()).isEqualTo("cb-scenario-7");
    }
}
