package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.SessionWindowDeriver;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.ContributingEventRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * [ANCHOR-CONSOL] Real-Postgres integration test of the consolidation fold — the true round-trip
 * (real Flyway V1+V2, real {@code INSERT ... ON CONFLICT (event_id) DO NOTHING}, real
 * {@code SELECT ... FOR UPDATE}). Kafka is disabled; the fold is driven directly through
 * {@link PatternConsolidationService}.
 *
 * <p>Covers: AC-C1 (same anchor across sub-runs -> ONE row, summed occurrences), AC-C3 (idempotent
 * redelivery does not double-count — the belt-and-braces {@code contributing_event} guard),
 * AC-C6 (order-independence — same set in reverse order yields identical aggregates),
 * AC-C7 ({@code sessionWindow} recomputed from combined timing), and AC-C8 (concurrent folds of one
 * anchor serialize on the row lock — no lost update).
 *
 * <p>{@code @Tag("integration")}: excluded from the default {@code build} per repo convention;
 * runnable via {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class PatternConsolidationServiceIT {

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
        // Keep the app from dialling real collaborators — this IT drives consolidation directly.
        registry.add("pattern-manager.integration.mode", () -> "mock");
    }

    @Autowired private PatternConsolidationService consolidationService;
    @Autowired private PatternRepository patternRepository;
    @Autowired private ContributingEventRepository contributingEventRepository;
    @Autowired private SessionWindowDeriver sessionWindowDeriver;

    // AC-C1: two sub-run mined events, same anchor identity, distinct eventIds -> ONE row, summed occ.
    @Test
    void sameAnchorAcrossSubRunsConsolidatesToOneRowSumsOccurrences() {
        String anchor = "SC-FIBER-" + UUID.randomUUID();
        EnrichedPattern e1 = anchored(anchor, "snap-1", "cb-1", 10, 0.4, "w1");
        EnrichedPattern e2 = anchored(anchor, "snap-1", "cb-1", 30, 0.6, "w2");

        ConsolidationOutcome o1 = consolidationService.consolidate(e1, id(), "pattern-miner");
        ConsolidationOutcome o2 = consolidationService.consolidate(e2, id(), "pattern-miner");

        assertThat(o1.created()).isTrue();
        assertThat(o2.created()).isFalse();
        assertThat(o2.folded()).isTrue();
        assertThat(o1.patternId()).isEqualTo(o2.patternId());
        assertThat(o1.patternId())
                .isEqualTo(UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", anchor));

        PatternEntity row = patternRepository.findById(o1.patternId()).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(40); // 10 + 30
        // occurrence-weighted mean support: (0.4*10 + 0.6*30)/40 = 0.55
        assertThat(row.getSupport()).isCloseTo(0.55, within(1e-9));
        assertThat(contributingEventRepository.countByPatternId(o1.patternId())).isEqualTo(2);
    }

    // AC-C3: re-delivering the SAME eventId does NOT double-count (belt-and-braces guard bypasses the
    // processed_event gate by calling consolidate directly with the same eventId again).
    @Test
    void redeliveredMinedEventDoesNotDoubleCount() {
        String anchor = "SC-BGP-" + UUID.randomUUID();
        EnrichedPattern e1 = anchored(anchor, "snap-1", "cb-1", 10, 0.4, "w1");
        EnrichedPattern e2 = anchored(anchor, "snap-1", "cb-1", 30, 0.6, "w2");
        String eid2 = id();

        UUID pid = consolidationService.consolidate(e1, id(), "pattern-miner").patternId();
        consolidationService.consolidate(e2, eid2, "pattern-miner");
        int afterFirst = patternRepository.findById(pid).orElseThrow().getInstanceCount();

        // Re-deliver e2 with the SAME eventId -> contributing_event ON CONFLICT DO NOTHING -> no-op.
        ConsolidationOutcome replay = consolidationService.consolidate(e2, eid2, "pattern-miner");

        assertThat(replay.folded()).isFalse();
        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(afterFirst); // 40, unchanged
        assertThat(contributingEventRepository.countByPatternId(pid)).isEqualTo(2);
    }

    // AC-C6: fold the SAME set of events in reverse order -> byte-identical final aggregates.
    @Test
    void aggregateIsOrderIndependent() {
        String anchorA = "SC-A-" + UUID.randomUUID();
        String anchorB = "SC-B-" + UUID.randomUUID();
        EnrichedPattern a1 = anchored(anchorA, "snap-1", "cb-1", 10, 0.4, "w1");
        EnrichedPattern a2 = anchored(anchorA, "snap-1", "cb-1", 30, 0.6, "w2");
        EnrichedPattern b1 = anchored(anchorB, "snap-1", "cb-1", 10, 0.4, "w1");
        EnrichedPattern b2 = anchored(anchorB, "snap-1", "cb-1", 30, 0.6, "w2");

        UUID pa = consolidationService.consolidate(a1, id(), "pattern-miner").patternId();
        consolidationService.consolidate(a2, id(), "pattern-miner");

        // Reverse order for anchor B.
        UUID pb = consolidationService.consolidate(b2, id(), "pattern-miner").patternId();
        consolidationService.consolidate(b1, id(), "pattern-miner");

        PatternEntity rowA = patternRepository.findById(pa).orElseThrow();
        PatternEntity rowB = patternRepository.findById(pb).orElseThrow();
        assertThat(rowB.getInstanceCount()).isEqualTo(rowA.getInstanceCount());
        assertThat(rowB.getSupport()).isCloseTo(rowA.getSupport(), within(1e-9));
        assertThat(rowB.getSessionWindowMs()).isEqualTo(rowA.getSessionWindowMs());
        assertThat(rowB.getSessionWindowType()).isEqualTo(rowA.getSessionWindowType());
    }

    // AC-C7: after a fold, session_window equals derive(combinedTiming) for the aggregated timing.
    @Test
    void sessionWindowRecomputedFromCombinedTiming() {
        String anchor = "SC-SW-" + UUID.randomUUID();
        EnrichedPattern e1 = anchoredTiming(anchor, 10,
                Map.of("timeframeMs", 10_000, "medianInterArrivalMs", 4000,
                        "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1000), "w1");
        EnrichedPattern e2 = anchoredTiming(anchor, 30,
                Map.of("timeframeMs", 20_000, "medianInterArrivalMs", 5000,
                        "maxInterArrivalMs", 9000, "stddevInterArrivalMs", 2000), "w2");

        UUID pid = consolidationService.consolidate(e1, id(), "pattern-miner").patternId();
        consolidationService.consolidate(e2, id(), "pattern-miner");

        Map<String, Object> combined = PatternAggregator.combineTiming(
                e1.timing(), 10, e2.timing(), 30);
        DerivedSessionWindow expected = sessionWindowDeriver.derive(combined);

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getSessionWindowMs()).isEqualTo(expected.windowMs());
        assertThat(row.getSessionWindowType()).isEqualTo(expected.type().wire());
    }

    // AC-C8: two threads fold two distinct events for ONE anchor concurrently -> FOR UPDATE
    // serializes them; no lost update.
    @Test
    void concurrentFoldsOfOneAnchorSerializeNoLostUpdate() throws Exception {
        String anchor = "SC-RACE-" + UUID.randomUUID();
        // Create the row first (single contributor), so both threads take the fold path.
        EnrichedPattern seed = anchored(anchor, "snap-1", "cb-1", 5, 0.5, "w0");
        UUID pid = consolidationService.consolidate(seed, id(), "pattern-miner").patternId();

        EnrichedPattern f1 = anchored(anchor, "snap-1", "cb-1", 10, 0.4, "w1");
        EnrichedPattern f2 = anchored(anchor, "snap-1", "cb-1", 30, 0.6, "w2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Runnable r1 = fold(f1, start, err);
        Runnable r2 = fold(f2, start, err);
        pool.submit(r1);
        pool.submit(r2);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(err.get()).isNull();

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getInstanceCount()).isEqualTo(45); // 5 + 10 + 30, no lost update
        assertThat(contributingEventRepository.countByPatternId(pid)).isEqualTo(3);
    }

    private Runnable fold(EnrichedPattern e, CountDownLatch start, AtomicReference<Throwable> err) {
        return () -> {
            try {
                start.await();
                consolidationService.consolidate(e, id(), "pattern-miner");
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        };
    }

    // --- helpers ---

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static EnrichedPattern anchored(String anchor, String snap, String cb, int occ,
            double support, String windowId) {
        return anchoredTiming(anchor, occ,
                Map.of("timeframeMs", 9000, "medianInterArrivalMs", 4500,
                        "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1200), windowId,
                snap, cb, support);
    }

    private static EnrichedPattern anchoredTiming(String anchor, int occ, Map<String, Object> timing,
            String windowId) {
        return anchoredTiming(anchor, occ, timing, windowId, "snap-1", "cb-1", 0.5);
    }

    private static EnrichedPattern anchoredTiming(String anchor, int occ, Map<String, Object> timing,
            String windowId, String snap, String cb, double support) {
        return new EnrichedPattern(
                "trail-1", List.of("LOS", "LinkDown", "bgpPeerDown"), "LOS",
                support, 0.8, 3.0, timing,
                new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                "CB-1", "confirmed", true, null, occ,
                List.of(new com.acp.patternmanager.enrichment.SupportingInstance(windowId, snap, null)),
                "core-ip", snap, cb, anchor, windowId);
    }
}
