package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acp.patternmanager.api.PatternViewMapper;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.enrichment.SampleAlarm;
import com.acp.patternmanager.enrichment.SupportingInstance;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.ContributingEventRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.acp.patternmanager.store.repo.PatternTrailRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [SIG-FOLD] Real-Postgres integration test of the unexplained-pattern signature fold — the true
 * round-trip (real Flyway V1..V4, real {@code INSERT ... ON CONFLICT} guards, real
 * {@code SELECT ... FOR UPDATE}). Kafka disabled; the fold is driven directly through
 * {@link PatternConsolidationService} and served through {@link PatternViewMapper} (as the read API
 * does). Covers AC-SF-1..3, AC-SF-6, AC-SF-8, AC-SF-9, AC-SF-12..16.
 *
 * <p>{@code @Tag("integration")}: run via {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SignatureFoldIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("correlation")
            .withUsername("correlation")
            .withPassword("correlation");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pattern-manager.kafka.enabled", () -> "false");
        registry.add("pattern-manager.integration.mode", () -> "mock");
    }

    @Autowired private PatternConsolidationService consolidationService;
    @Autowired private PatternRepository patternRepository;
    @Autowired private ContributingEventRepository contributingEventRepository;
    @Autowired private PatternTrailRepository patternTrailRepository;
    @Autowired private PatternViewMapper viewMapper;

    // AC-SF-1: same signature, DIFFERENT trails + windows -> ONE row, instanceCount = sum.
    @Test
    void signatureFoldsAcrossTrails() {
        String snap = snap();
        List<String> seq = List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded");
        EnrichedPattern e1 = unexplained("trail-1", seq, "w1", snap, 3, 0.6);
        EnrichedPattern e2 = unexplained("trail-2", seq, "w2", snap, 2, 0.4);

        ConsolidationOutcome o1 = consolidationService.consolidate(e1, id(), "pattern-miner");
        ConsolidationOutcome o2 = consolidationService.consolidate(e2, id(), "pattern-miner");

        assertThat(o1.created()).isTrue();
        assertThat(o2.folded()).isTrue();
        assertThat(o1.patternId()).isEqualTo(o2.patternId());
        assertThat(o1.patternId()).isEqualTo(UuidV5.signatureIdentity(seq, "core-ip", snap));

        PatternEntity row = patternRepository.findById(o1.patternId()).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(5); // 3 + 2
        assertThat(row.getOccurrenceCount()).isEqualTo(2);
        assertThat(row.getTrailCount()).isEqualTo(2);
    }

    // [PATTERN-NAME] A freshly-created unexplained pattern head persists the deterministic readable
    // pattern_name (Pattern Manager owns + persists it; the DB is the SSoT). The name is stable per
    // signature, so a later fold leaves it unchanged, and the mapper serves the persisted value.
    @Test
    void unexplainedPatternPersistsAndServesDeterministicPatternName() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown"); // rootCauseAlarmType = "LOS"
        EnrichedPattern e1 = unexplained("trail-1", seq, "w1", snap, 3, 0.6);

        UUID pid = consolidationService.consolidate(e1, id(), "pattern-miner").patternId();

        PatternEntity created = patternRepository.findById(pid).orElseThrow();
        String expected =
                com.acp.patternmanager.naming.PatternNaming.patternName("LOS", pid.toString());
        assertThat(created.getPatternName()).isEqualTo(expected);
        assertThat(created.getPatternName()).startsWith("Loss of Signal Cascade · ");
        // Served on the read model straight from the persisted column.
        assertThat(viewMapper.toView(created).patternName()).isEqualTo(expected);

        // A later fold of the SAME signature does NOT change the stable name.
        EnrichedPattern e2 = unexplained("trail-2", seq, "w2", snap, 2, 0.4);
        consolidationService.consolidate(e2, id(), "pattern-miner");
        assertThat(patternRepository.findById(pid).orElseThrow().getPatternName())
                .isEqualTo(expected);
    }

    // AC-SF-2: same signature, DIFFERENT windows, SAME trail -> ONE row, summed instanceCount.
    @Test
    void signatureFoldsAcrossWindowsSameTrail() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown");
        EnrichedPattern e1 = unexplained("trail-X", seq, "w1", snap, 4, 0.5);
        EnrichedPattern e2 = unexplained("trail-X", seq, "w2", snap, 6, 0.5);

        UUID pid = consolidationService.consolidate(e1, id(), "pattern-miner").patternId();
        ConsolidationOutcome o2 = consolidationService.consolidate(e2, id(), "pattern-miner");

        assertThat(o2.folded()).isTrue();
        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(10);
        assertThat(row.getOccurrenceCount()).isEqualTo(2);
        assertThat(row.getTrailCount()).isEqualTo(1); // same trail -> distinct trails = 1
    }

    // AC-SF-3/4/5/10: different sequences/order/repeats/snapshot -> distinct rows.
    @Test
    void differentShapesStayDistinct() {
        String snap = snap();
        UUID a = consolidationService.consolidate(
                unexplained("t", List.of("IPLinkDown", "LinkDown"), "w1", snap, 1, 0.5), id(), "s").patternId();
        UUID b = consolidationService.consolidate(
                unexplained("t", List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded"), "w2", snap, 1, 0.5), id(), "s").patternId();
        UUID order = consolidationService.consolidate(
                unexplained("t", List.of("LinkDown", "IPLinkDown"), "w3", snap, 1, 0.5), id(), "s").patternId();
        UUID repeats = consolidationService.consolidate(
                unexplained("t", List.of("IPLinkDown", "LinkDown", "IPLinkDown"), "w4", snap, 1, 0.5), id(), "s").patternId();
        UUID snap2 = consolidationService.consolidate(
                unexplained("t", List.of("IPLinkDown", "LinkDown"), "w5", snap(), 1, 0.5), id(), "s").patternId();

        assertThat(List.of(a, b, order, repeats, snap2)).doesNotHaveDuplicates();
    }

    // AC-SF-6: fold aggregates occurrence-weighted metrics. (inst=3,supp=0.6)+(inst=2,supp=0.4)
    // -> instanceCount=5, support=(0.6*3+0.4*2)/5 = 0.52.
    @Test
    void foldAggregatesWeightedMetrics() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown", "PortDown");
        EnrichedPattern e1 = unexplained("t1", seq, "w1", snap, 3, 0.6);
        EnrichedPattern e2 = unexplained("t2", seq, "w2", snap, 2, 0.4);

        UUID pid = consolidationService.consolidate(e1, id(), "s").patternId();
        consolidationService.consolidate(e2, id(), "s");

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(5);
        assertThat(row.getSupport()).isCloseTo(0.52, within(1e-9));
    }

    // AC-SF-7: exactly one create (=> one PatternDiscoveredEvent) per signature; folds create nothing.
    @Test
    void emitsOncePerSignature() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown");
        int creates = 0;
        for (int i = 0; i < 5; i++) {
            ConsolidationOutcome o = consolidationService.consolidate(
                    unexplained("trail-" + i, seq, "w" + i, snap, 1, 0.5), id(), "s");
            if (o.created()) {
                creates++;
            }
        }
        assertThat(creates).isEqualTo(1);
    }

    // AC-SF-8: fold-keeps-first sample alarms (the second event's sample is NOT appended/replaced).
    @Test
    void foldKeepsFirstSampleAlarms() {
        String snap = snap();
        List<String> seq = List.of("FiberFault", "LinkDown");
        EnrichedPattern first = unexplainedSample("t1", seq, "w1", snap, sample("alarmA"));
        EnrichedPattern second = unexplainedSample("t2", seq, "w2", snap, sample("alarmB"));

        UUID pid = consolidationService.consolidate(first, id(), "s").patternId();
        consolidationService.consolidate(second, id(), "s");

        PatternView view = viewMapper.toView(patternRepository.findById(pid).orElseThrow());
        assertThat(view.sampleAlarms()).extracting(SampleAlarmView::alarmId).containsExactly("alarmA");
    }

    // AC-SF-9 + AC-SF-15: idempotent replay -> occ/inst/trail + lastSeen all unchanged.
    @Test
    void replayDoesNotDoubleCountOrBumpLastSeen() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown");
        EnrichedPattern e1 = unexplained("t1", seq, "w1", snap, 3, 0.5);
        EnrichedPattern e2 = unexplained("t2", seq, "w2", snap, 2, 0.5);
        String eid2 = id();

        UUID pid = consolidationService.consolidate(e1, id(), "s").patternId();
        consolidationService.consolidate(e2, eid2, "s");
        PatternEntity afterFold = patternRepository.findById(pid).orElseThrow();
        int occ = afterFold.getOccurrenceCount();
        int inst = afterFold.getInstanceCount();
        int trails = afterFold.getTrailCount();
        OffsetDateTime lastSeen = afterFold.getLastSeen();

        // Re-deliver e2 with the SAME eventId -> contributing_event ON CONFLICT -> no-op.
        ConsolidationOutcome replay = consolidationService.consolidate(e2, eid2, "s");

        assertThat(replay.folded()).isFalse();
        PatternEntity after = patternRepository.findById(pid).orElseThrow();
        assertThat(after.getOccurrenceCount()).isEqualTo(occ);
        assertThat(after.getInstanceCount()).isEqualTo(inst);
        assertThat(after.getTrailCount()).isEqualTo(trails);
        assertThat(after.getLastSeen()).isEqualTo(lastSeen);
        assertThat(patternTrailRepository.countByPatternId(pid)).isEqualTo(trails);
    }

    // AC-SF-12: live-evidence case: 12 events across 11 distinct trails -> ONE row, occ=12, trail=11.
    @Test
    void liveEvidenceTwelveOccurrencesElevenTrails() {
        String snap = snap();
        List<String> seq = List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded");
        UUID pid = null;
        for (int i = 0; i < 12; i++) {
            // 12 events, 11 distinct trails: events 0 and 1 share trail-0.
            String trail = "trail-" + Math.max(0, i - 1);
            ConsolidationOutcome o = consolidationService.consolidate(
                    unexplained(trail, seq, "w" + i, snap, 1, 0.5), id(), "s");
            pid = o.patternId();
        }
        assertThat(patternRepository.findById(pid)).isPresent();
        PatternView view = viewMapper.toView(patternRepository.findById(pid).orElseThrow());
        assertThat(view.occurrenceCount()).isEqualTo(12);
        assertThat(view.trailCount()).isEqualTo(11);
    }

    // AC-SF-13: occurrenceCount vs trailCount are distinct. 3 events: 2 on trail-X, 1 on trail-Y
    // -> occ=3, trail=2.
    @Test
    void occurrenceCountAndTrailCountAreDistinct() {
        String snap = snap();
        List<String> seq = List.of("LOS", "PortDown");
        UUID pid = consolidationService.consolidate(unexplained("trail-X", seq, "w1", snap, 1, 0.5), id(), "s").patternId();
        consolidationService.consolidate(unexplained("trail-X", seq, "w2", snap, 1, 0.5), id(), "s");
        consolidationService.consolidate(unexplained("trail-Y", seq, "w3", snap, 1, 0.5), id(), "s");

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getOccurrenceCount()).isEqualTo(3);
        assertThat(row.getTrailCount()).isEqualTo(2);
    }

    // AC-SF-14: firstSeen unchanged by fold, lastSeen bumped to the latest occurrence.
    @Test
    void firstAndLastSeenTracked() {
        String snap = snap();
        List<String> seq = List.of("LOS", "LinkDown");
        UUID pid = consolidationService.consolidate(unexplained("t1", seq, "w1", snap, 1, 0.5), id(), "s").patternId();
        PatternEntity created = patternRepository.findById(pid).orElseThrow();
        OffsetDateTime firstSeen = created.getFirstSeen();

        consolidationService.consolidate(unexplained("t2", seq, "w2", snap, 1, 0.5), id(), "s");

        PatternEntity folded = patternRepository.findById(pid).orElseThrow();
        assertThat(folded.getFirstSeen()).isEqualTo(firstSeen); // unchanged
        assertThat(folded.getLastSeen()).isAfterOrEqualTo(firstSeen); // bumped to latest
    }

    // AC-SF-16: anchored patterns also expose the 4 impact fields (non-null, correct).
    @Test
    void anchoredExposesImpactFields() {
        String anchor = "SC-FIBER-" + UUID.randomUUID();
        EnrichedPattern e1 = anchored(anchor, 10, 0.4, "w1", "trail-a");
        EnrichedPattern e2 = anchored(anchor, 30, 0.6, "w2", "trail-b");

        UUID pid = consolidationService.consolidate(e1, id(), "s").patternId();
        consolidationService.consolidate(e2, id(), "s");

        PatternView view = viewMapper.toView(patternRepository.findById(pid).orElseThrow());
        assertThat(view.occurrenceCount()).isEqualTo(2);
        assertThat(view.trailCount()).isEqualTo(2);
        assertThat(view.firstSeen()).isNotNull();
        assertThat(view.lastSeen()).isNotNull();
        assertThat(contributingEventRepository.countByPatternId(pid)).isEqualTo(2);
    }

    // --- helpers ---

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String snap() {
        return "snap-" + UUID.randomUUID();
    }

    private static EnrichedPattern unexplained(String trailId, List<String> seq, String windowId,
            String snapshotId, int instanceCount, double support) {
        return new EnrichedPattern(
                trailId, seq, seq.get(0), support, 0.7, 2.0,
                Map.of("timeframeMs", 5000, "medianInterArrivalMs", 2500,
                        "maxInterArrivalMs", 4000, "stddevInterArrivalMs", 800),
                new DerivedSessionWindow(20_000, DerivedSessionWindow.WindowType.GAP_BASED),
                null, "unexplained", true, null, instanceCount,
                List.of(new SupportingInstance(windowId, snapshotId, null)),
                List.of(), "core-ip", snapshotId, "cb-1", null, windowId);
    }

    private static EnrichedPattern unexplainedSample(String trailId, List<String> seq, String windowId,
            String snapshotId, List<SampleAlarm> samples) {
        return new EnrichedPattern(
                trailId, seq, seq.get(0), 0.5, 0.7, 2.0,
                Map.of("timeframeMs", 5000, "medianInterArrivalMs", 2500,
                        "maxInterArrivalMs", 4000, "stddevInterArrivalMs", 800),
                new DerivedSessionWindow(20_000, DerivedSessionWindow.WindowType.GAP_BASED),
                null, "unexplained", true, null, 1,
                List.of(new SupportingInstance(windowId, snapshotId, null)),
                samples, "core-ip", snapshotId, "cb-1", null, windowId);
    }

    private static List<SampleAlarm> sample(String alarmId) {
        return List.of(new SampleAlarm(alarmId, "FiberFault",
                OffsetDateTime.parse("2026-06-20T14:03:11Z"), "OpticalPort:node-1", "major"));
    }

    private static EnrichedPattern anchored(String anchor, int occ, double support, String windowId,
            String trailId) {
        return new EnrichedPattern(
                trailId, List.of("LOS", "LinkDown", "bgpPeerDown"), "LOS",
                support, 0.8, 3.0,
                Map.of("timeframeMs", 9000, "medianInterArrivalMs", 4500,
                        "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1200),
                new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                "CB-1", "confirmed", true, null, occ,
                List.of(new SupportingInstance(windowId, "snap-1", null)),
                List.of(), "core-ip", "snap-1", "cb-1", anchor, windowId);
    }
}
