package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.config.SampleAlarmProperties;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.DerivedSessionWindow.WindowType;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.enrichment.SampleAlarm;
import com.acp.patternmanager.store.entity.LifecycleTransitionEntity;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SampleAlarmEntity;
import com.acp.patternmanager.store.repo.LifecycleTransitionRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Sole-writer behaviour of {@link PatternStoreService#persistApprovedSeed}: a seed row is persisted
 * directly in the {@code approved} lifecycle, carrying the same create-time fields a mined pattern
 * has (sequence, sample alarms, session window, name), with a complete two-step audit trail
 * ({@code - -> draft}, {@code draft -> approved}) — so a seed is served by
 * {@code GET /patterns?lifecycle=approved} exactly like a human-approved pattern.
 */
@ExtendWith(MockitoExtension.class)
class PatternStoreSeedTest {

    @Mock private PatternRepository patternRepository;
    @Mock private LifecycleTransitionRepository transitionRepository;

    private PatternStoreService store() {
        return new PatternStoreService(patternRepository, transitionRepository, null, null,
                new ObjectMapper(), new SampleAlarmProperties(10));
    }

    private EnrichedPattern seed() {
        return new EnrichedPattern(
                "seed:core-ip/seed/iplink-to-vpn",
                List.of("LinkDown", "LSPDown", "VPNReachabilityLoss"),
                "LinkDown",
                0.45, 0.91, 3.0,
                java.util.Map.of("medianGapMs", 5000),
                new DerivedSessionWindow(45000, WindowType.GAP_BASED),
                "seed/iplink",
                "confirmed",
                true, null,
                3,
                List.of(),
                List.of(
                    new SampleAlarm("s1", "LinkDown",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), "IPLink:x1", "major"),
                    new SampleAlarm("s2", "LSPDown",
                        OffsetDateTime.parse("2026-01-01T00:00:04Z"), "LSP:x2", "major"),
                    new SampleAlarm("s3", "VPNReachabilityLoss",
                        OffsetDateTime.parse("2026-01-01T00:00:08Z"), "VPNService:x3", "critical")),
                "core-ip", null, null, null, null);
    }

    @Test
    void persistsSeedDirectlyAsApprovedWithAuditTrail() {
        when(patternRepository.save(any(PatternEntity.class))).thenAnswer(i -> i.getArgument(0));
        UUID id = UuidV5.from("core-ip/seed/iplink-to-vpn");

        PatternEntity saved = store().persistApprovedSeed(id, seed(), "seed",
                OffsetDateTime.parse("2026-07-08T00:00:00Z"));

        // Directly approved (never draft), name computed, provenance carried.
        assertThat(saved.getLifecycle()).isEqualTo("approved");
        assertThat(saved.getPatternName()).isNotBlank();
        assertThat(saved.getRootCauseAlarmType()).isEqualTo("LinkDown");
        assertThat(saved.getTrailId()).isEqualTo("seed:core-ip/seed/iplink-to-vpn");
        assertThat(saved.getSessionWindowMs()).isEqualTo(45000);
        assertThat(saved.getSessionWindowType()).isEqualTo("gap-based");
        assertThat(saved.getReconcileStatus()).isEqualTo("confirmed");
        assertThat(saved.isStructurallyValidated()).isTrue();

        // Ordered sequence + sample alarms persisted.
        assertThat(saved.getSequenceElements()).extracting(e -> e.getAlarmType())
                .containsExactly("LinkDown", "LSPDown", "VPNReachabilityLoss");
        assertThat(saved.getSampleAlarms()).extracting(SampleAlarmEntity::getManagedObjectId)
                .containsExactly("IPLink:x1", "LSP:x2", "VPNService:x3");

        // Two audit rows: implicit discovery, then the seed approval.
        ArgumentCaptor<LifecycleTransitionEntity> tx =
                ArgumentCaptor.forClass(LifecycleTransitionEntity.class);
        verify(transitionRepository, times(2)).save(tx.capture());
        assertThat(tx.getAllValues()).extracting(t -> t.getFromState() + "->" + t.getToState())
                .containsExactly("-->draft", "draft->approved");
    }
}
