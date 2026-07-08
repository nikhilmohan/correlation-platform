package com.acp.patternmanager.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.api.PatternQueryService;
import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.api.dto.SequenceElementView;
import java.util.HashMap;
import java.util.Map;
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
 * Real-Postgres integration test for the pre-approved pattern seed (the live catch-net gate that a
 * mock-only unit test cannot provide). Boots the full app so the startup {@link PatternSeedLoader}
 * runs against real Flyway + real JPA, then asserts the shipped pack:
 * <ul>
 *   <li>persists every seed directly in the {@code approved} lifecycle — so
 *       {@code GET /patterns?lifecycle=approved} (the Correlation Engine's read) serves them out of
 *       the box, no mining required;</li>
 *   <li>serves each with the FULL CE-consumed shape: ordered {@code sequence}, {@code sessionWindow},
 *       {@code rootCauseAlarmType}, and {@code sampleAlarms} whose {@code managedObjectId} prefixes
 *       witness every sequence alarmType's objectType (so CE's compatibility index accepts it against
 *       a fresh topology snapshot);</li>
 *   <li>is idempotent — a second {@code loadPack} of the same pack loads 0 new patterns.</li>
 * </ul>
 *
 * <p>{@code @Tag("integration")}: excluded from the default {@code build}; run via
 * {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class PatternSeedLoaderIT {

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
        // Exercise the loader end-to-end but keep this test's assertions independent of the
        // patterns.approved emit path (kafka disabled) by turning the event emission off.
        registry.add("pattern-manager.seed.emit-approved-events", () -> "false");
    }

    @Autowired private PatternQueryService queryService;
    @Autowired private PatternSeedLoader seedLoader;

    // The startup seed makes approved patterns queryable out of the box (no mining).
    @Test
    void shippedSeedIsQueryableAsApprovedWithCeCompatibleShape() {
        PatternPage approved = queryService.list("approved", 100, 0, "-createdAt");

        assertThat(approved.total()).isGreaterThanOrEqualTo(5);
        for (PatternView v : approved.items()) {
            assertThat(v.lifecycle()).isEqualTo("approved");
            assertThat(v.sequence()).isNotEmpty();
            assertThat(v.sessionWindow().windowMs()).isPositive();
            assertThat(v.rootCauseAlarmType()).isNotBlank();

            // The alarmType -> objectType witness map CE derives (PatternViewMapper.sampleAlarmObjectTypes):
            // objectType is the managedObjectId prefix. Every sequence alarmType must be witnessed and
            // the root type present — else CE fail-safe-excludes the pattern from its index.
            Map<String, String> witness = new HashMap<>();
            for (SampleAlarmView sa : v.sampleAlarms()) {
                int c = sa.managedObjectId().indexOf(':');
                assertThat(c).isGreaterThan(0);
                witness.putIfAbsent(sa.alarmType(), sa.managedObjectId().substring(0, c));
            }
            for (SequenceElementView el : v.sequence()) {
                assertThat(witness).containsKey(el.alarmType());
            }
            assertThat(witness).containsKey(v.rootCauseAlarmType());
        }
    }

    // Idempotency at the DB layer: re-running the loader loads 0 new patterns (safe restart).
    @Test
    void reloadingTheSamePackIsIdempotent() throws Exception {
        long before = queryService.list("approved", 200, 0, "-createdAt").total();
        int reloaded = seedLoader.loadPack("seed/core-ip-patterns.json");
        long after = queryService.list("approved", 200, 0, "-createdAt").total();

        assertThat(reloaded).isZero();
        assertThat(after).isEqualTo(before);
    }
}
