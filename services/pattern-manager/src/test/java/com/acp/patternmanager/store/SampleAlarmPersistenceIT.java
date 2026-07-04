package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.api.PatternViewMapper;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.enrichment.SampleAlarm;
import com.acp.patternmanager.enrichment.SupportingInstance;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Real-Postgres integration test of sample-alarm persist -> serve (spec-sample-alarms E1-E4). The
 * true round-trip: real Flyway V1+V2+V3, real cascade + orphanRemoval, real dedupe / fold guards. The
 * fold-keeps-first-sample rule (DA-1) and the dup-key sidestep are invisible without a real DB, so
 * this IT is essential. Kafka disabled; the persist is driven directly through
 * {@link PatternConsolidationService} and served through {@link PatternViewMapper} (as the read API
 * does). {@code cap-k} set to 3 to also exercise AC-SA-6 defensively at the DB layer.
 *
 * <p>{@code @Tag("integration")}: excluded from the default {@code build}; run via
 * {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SampleAlarmPersistenceIT {

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
        // K=3 so a 4th+ sample is defensively dropped at ingest (AC-SA-6 at the DB layer).
        registry.add("pattern-manager.sample-alarms.cap-k", () -> "3");
    }

    @Autowired private PatternConsolidationService consolidationService;
    @Autowired private PatternRepository patternRepository;
    @Autowired private PatternViewMapper viewMapper;

    // E1: mined event WITH sampleAlarms -> persisted; served in order with all 5 fields.
    @Test
    void persistsAndServesSampleInOrder() {
        String anchor = "SC-SAMPLE-" + UUID.randomUUID();
        EnrichedPattern e = anchored(anchor, sample("alm-1", "alm-2", "alm-3"));

        UUID pid = consolidationService.consolidate(e, id(), "pattern-miner").patternId();

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getSampleAlarms()).hasSize(3);

        PatternView view = viewMapper.toView(row);
        assertThat(view.sampleAlarms()).extracting(SampleAlarmView::alarmId)
                .containsExactly("alm-1", "alm-2", "alm-3"); // deterministic position order
        SampleAlarmView first = view.sampleAlarms().get(0);
        assertThat(first.alarmType()).isEqualTo("FiberFault");
        assertThat(first.raisedAt()).isNotNull();
        assertThat(first.managedObjectId()).contains(":");
        assertThat(first.perceivedSeverity()).isNotBlank();
    }

    // E2 (AC-SA-7): redelivering the SAME eventId does not duplicate sample rows.
    @Test
    void redeliveryDoesNotDuplicateSample() {
        String anchor = "SC-SAMPLE-RD-" + UUID.randomUUID();
        EnrichedPattern e = anchored(anchor, sample("alm-1", "alm-2", "alm-3"));
        String eid = id();

        UUID pid = consolidationService.consolidate(e, eid, "pattern-miner").patternId();
        int afterFirst = patternRepository.findById(pid).orElseThrow().getSampleAlarms().size();
        // Re-deliver the SAME eventId — per-event identity re-maps to the same row, an idempotent no-op.
        consolidationService.consolidate(e, eid, "pattern-miner");

        assertThat(patternRepository.findById(pid).orElseThrow().getSampleAlarms())
                .hasSize(afterFirst); // 3, unchanged
    }

    // E3 (DA-1): a SECOND event with the same anchor (a fold) keeps the FIRST contributor's sample
    // only — not appended, not replaced. One bounded set; no replace-collection dup-key error.
    @Test
    void foldKeepsFirstContributorsSample() {
        String anchor = "SC-SAMPLE-FOLD-" + UUID.randomUUID();
        EnrichedPattern first = anchored(anchor, sample("first-1", "first-2", "first-3"));
        EnrichedPattern second = anchored(anchor, sample("second-1", "second-2", "second-3"));

        UUID pid = consolidationService.consolidate(first, id(), "pattern-miner").patternId();
        ConsolidationOutcome folded = consolidationService.consolidate(second, id(), "pattern-miner");

        assertThat(folded.folded()).isTrue();
        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        // ONE bounded sample = the first contributor's; the second event's sample is NOT appended.
        assertThat(row.getSampleAlarms()).hasSize(3);
        assertThat(viewMapper.toView(row).sampleAlarms()).extracting(SampleAlarmView::alarmId)
                .containsExactly("first-1", "first-2", "first-3");
    }

    // E4 (AC-SA-5b): event WITHOUT sampleAlarms -> pattern persisted, zero sample rows, served [].
    @Test
    void backwardCompatEventWithoutSampleServedAsEmpty() {
        String anchor = "SC-SAMPLE-EMPTY-" + UUID.randomUUID();
        EnrichedPattern e = anchored(anchor, List.of());

        UUID pid = consolidationService.consolidate(e, id(), "pattern-miner").patternId();

        PatternEntity row = patternRepository.findById(pid).orElseThrow();
        assertThat(row.getSampleAlarms()).isEmpty();
        assertThat(viewMapper.toView(row).sampleAlarms()).isNotNull().isEmpty();
    }

    // AC-SA-6 at the DB layer: K=3 and 5 sample entries -> at most 3 rows persisted.
    @Test
    void capsSampleToKAtDbLayer() {
        String anchor = "SC-SAMPLE-CAP-" + UUID.randomUUID();
        EnrichedPattern e = anchored(anchor, sample("a", "b", "c", "d", "e"));

        UUID pid = consolidationService.consolidate(e, id(), "pattern-miner").patternId();

        assertThat(patternRepository.findById(pid).orElseThrow().getSampleAlarms()).hasSize(3);
    }

    // --- helpers ---

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static List<SampleAlarm> sample(String... alarmIds) {
        String[] types = {"FiberFault", "LinkDown", "PortDown"};
        java.util.List<SampleAlarm> out = new java.util.ArrayList<>();
        for (int i = 0; i < alarmIds.length; i++) {
            out.add(new SampleAlarm(alarmIds[i], types[i % types.length],
                    OffsetDateTime.parse("2026-06-20T14:03:1" + (i % 10) + "Z"),
                    "OpticalPort:node-" + i, "major"));
        }
        return out;
    }

    private static EnrichedPattern anchored(String anchor, List<SampleAlarm> samples) {
        return new EnrichedPattern(
                "trail-1", List.of("FiberFault", "LinkDown", "PortDown"), "FiberFault",
                0.4, 0.8, 3.0,
                java.util.Map.of("timeframeMs", 9000, "medianInterArrivalMs", 4500,
                        "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1200),
                new DerivedSessionWindow(30_000, DerivedSessionWindow.WindowType.GAP_BASED),
                "CB-1", "confirmed", true, null, 10,
                List.of(new SupportingInstance("w1", "snap-1", null)),
                samples,
                "core-ip", "snap-1", "cb-1", anchor, "w1");
    }
}
