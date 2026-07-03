package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.SessionWindowDeriver;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.ContributingEventRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.time.OffsetDateTime;
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
 * [ANCHOR-CONSOL] Fast Mockito tests of the consolidation IDENTITY + OUTCOME decisions (which row a
 * mined event folds into and whether it CREATED the row, so the caller emits a discovered event
 * exactly once). The numeric aggregation + recompute + concurrency are proven end-to-end against a
 * real Postgres in {@link PatternConsolidationServiceIT} (AC-C1/C3/C6/C7/C8); the pure math is in
 * {@link PatternAggregatorTest}.
 *
 * <p>Covers AC-C2 (emit-once decision), AC-C4 (unexplained stays distinct), AC-C5 (scope re-mint).
 */
@ExtendWith(MockitoExtension.class)
class PatternConsolidationServiceTest {

    @Mock private PatternRepository patternRepository;
    @Mock private ContributingEventRepository contributingEventRepository;
    @Mock private PatternStoreService storeService;
    @Mock private SessionWindowDeriver sessionWindowDeriver;

    private PatternConsolidationService service;

    @BeforeEach
    void setUp() {
        service = new PatternConsolidationService(
                patternRepository, contributingEventRepository, storeService, sessionWindowDeriver);
        lenient().when(sessionWindowDeriver.derive(any()))
                .thenReturn(new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED));
        lenient().when(storeService.createDraftRow(any(), any(), anyDouble(), any()))
                .thenAnswer(inv -> stubRow(inv.getArgument(0), inv.getArgument(1)));
    }

    // AC-C2 (create half): the FIRST contributor for an anchor identity CREATES the row -> the
    // caller is told created()=true, so exactly one PatternDiscoveredEvent is emitted.
    @Test
    void firstAnchoredContributorCreatesAndSignalsEmit() {
        EnrichedPattern e = anchored("SC-FIBER", "snap-1", "cb-1", 10, 0.4);
        UUID expectedId = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        when(patternRepository.findByIdForUpdate(expectedId)).thenReturn(Optional.empty());

        ConsolidationOutcome outcome = service.consolidate(e, randomEventId(), "pattern-miner");

        assertThat(outcome.patternId()).isEqualTo(expectedId);
        assertThat(outcome.created()).isTrue();
        assertThat(outcome.folded()).isFalse();
        verify(storeService).createDraftRow(eq(expectedId), eq(e), anyDouble(), any());
        verify(contributingEventRepository).insertIgnoreConflict(any(), eq(expectedId),
                eq("SC-FIBER"), eq(10), eq(0.4), any());
    }

    // AC-C2 (fold half): a LATER contributor for the same identity folds -> created()=false, so the
    // caller emits NO second discovered event.
    @Test
    void laterAnchoredContributorFoldsAndSignalsNoEmit() {
        EnrichedPattern e = anchored("SC-FIBER", "snap-1", "cb-1", 30, 0.6);
        UUID id = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        PatternEntity existing = stubRow(id, anchored("SC-FIBER", "snap-1", "cb-1", 10, 0.4));
        when(patternRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(contributingEventRepository.insertIgnoreConflict(any(), eq(id), anyString(), anyInt(),
                anyDouble(), any())).thenReturn(1); // new distinct contributor -> fold

        ConsolidationOutcome outcome = service.consolidate(e, randomEventId(), "pattern-miner");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.folded()).isTrue();
        verify(storeService, never()).createDraftRow(any(), any(), anyDouble(), any());
        // Aggregation ran: occurrences summed on the existing row.
        assertThat(existing.getInstanceCount()).isEqualTo(40);
    }

    // AC-C3 orchestration: a contributing_event ON CONFLICT (inserted==0) is a no-op fold.
    @Test
    void replayedContributorIsNoOp() {
        EnrichedPattern e = anchored("SC-FIBER", "snap-1", "cb-1", 30, 0.6);
        UUID id = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        PatternEntity existing = stubRow(id, anchored("SC-FIBER", "snap-1", "cb-1", 10, 0.4));
        when(patternRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(contributingEventRepository.insertIgnoreConflict(any(), eq(id), anyString(), anyInt(),
                anyDouble(), any())).thenReturn(0); // already present -> no-op

        ConsolidationOutcome outcome = service.consolidate(e, randomEventId(), "pattern-miner");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.folded()).isFalse();
        assertThat(existing.getInstanceCount()).isEqualTo(10); // unchanged
        verify(storeService, never()).save(existing);
    }

    // AC-C4: unexplained patterns (null anchorScenarioId) use the per-event identity, never the
    // anchor path -> each stays distinct, and no anchor lock/fold is taken.
    @Test
    void unexplainedUsesPerEventIdentityNotAnchor() {
        EnrichedPattern e = unexplained("trail-1", List.of("LOS", "LinkDown"), "w1", "snap-1");
        UUID expectedId = UuidV5.perEventIdentity("trail-1", List.of("LOS", "LinkDown"), "w1", "snap-1");
        when(patternRepository.findById(expectedId)).thenReturn(Optional.empty());

        ConsolidationOutcome outcome = service.consolidate(e, randomEventId(), "pattern-miner");

        assertThat(outcome.patternId()).isEqualTo(expectedId);
        assertThat(outcome.created()).isTrue();
        verify(patternRepository, never()).findByIdForUpdate(any());
        verify(contributingEventRepository, never())
                .insertIgnoreConflict(any(), any(), any(), anyInt(), anyDouble(), any());
    }

    @Test
    void twoUnexplainedDifferentWindowsAreDistinctRows() {
        EnrichedPattern e1 = unexplained("trail-1", List.of("LOS"), "w1", "snap-1");
        EnrichedPattern e2 = unexplained("trail-1", List.of("LOS"), "w2", "snap-1");
        when(patternRepository.findById(any())).thenReturn(Optional.empty());

        UUID id1 = service.consolidate(e1, randomEventId(), "pattern-miner").patternId();
        UUID id2 = service.consolidate(e2, randomEventId(), "pattern-miner").patternId();

        assertThat(id1).isNotEqualTo(id2);
    }

    // AC-C5: same anchorScenarioId but different snapshot/codebook version -> distinct identities.
    @Test
    void differentSnapshotOrCodebookDoesNotMerge() {
        EnrichedPattern snap1 = anchored("SC-FIBER", "snap-1", "cb-1", 10, 0.4);
        EnrichedPattern snap2 = anchored("SC-FIBER", "snap-2", "cb-1", 10, 0.4);
        EnrichedPattern cb2 = anchored("SC-FIBER", "snap-1", "cb-2", 10, 0.4);
        when(patternRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        service.consolidate(snap1, randomEventId(), "pattern-miner");
        service.consolidate(snap2, randomEventId(), "pattern-miner");
        service.consolidate(cb2, randomEventId(), "pattern-miner");
        verify(patternRepository, times(3)).findByIdForUpdate(ids.capture());

        assertThat(ids.getAllValues()).doesNotHaveDuplicates();
    }

    // --- helpers ---

    private static String randomEventId() {
        return UUID.randomUUID().toString();
    }

    private static EnrichedPattern anchored(String anchorScenarioId, String snapshotId,
            String codebookVersion, int occ, double support) {
        return new EnrichedPattern(
                "trail-1", List.of("LOS", "LinkDown", "bgpPeerDown"), "LOS",
                support, 0.8, 3.0,
                Map.of("timeframeMs", 9000, "medianInterArrivalMs", 4500,
                        "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1200),
                new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                "CB-1", "confirmed", true, null, occ, List.of(),
                "core-ip", snapshotId, codebookVersion, anchorScenarioId, "w-" + occ);
    }

    private static EnrichedPattern unexplained(String trailId, List<String> seq, String windowId,
            String snapshotId) {
        return new EnrichedPattern(
                trailId, seq, seq.get(0), 0.3, 0.7, 2.0,
                Map.of("timeframeMs", 5000),
                new DerivedSessionWindow(20_000, DerivedSessionWindow.WindowType.GAP_BASED),
                null, "unexplained", true, null, 5, List.of(),
                "core-ip", snapshotId, "cb-1", null, windowId);
    }

    /** A stand-in persisted row mirroring what createDraftRow would build (for the mocked store). */
    private static PatternEntity stubRow(UUID id, EnrichedPattern e) {
        PatternEntity row = new PatternEntity();
        row.setPatternId(id);
        row.setTrailId(e.trailId());
        row.setRootCauseAlarmType(e.rootCauseAlarmType());
        row.setSupport(e.support());
        row.setConfidence(e.confidence());
        row.setLift(e.lift());
        row.setInstanceCount(e.instanceCount());
        row.setRepresentativeWeight(e.support() * e.instanceCount());
        row.setSessionWindowMs(e.sessionWindow().windowMs());
        row.setSessionWindowType(e.sessionWindow().type().wire());
        row.setLifecycle("draft");
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }
}
