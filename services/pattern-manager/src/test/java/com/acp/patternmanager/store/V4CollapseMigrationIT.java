package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
 * [SIG-FOLD] Real-Postgres integration test of the V4 one-time collapse migration (AC-SF-17,
 * AC-SF-18). The migration already ran (as a no-op) at startup on the empty store. This test
 * pre-seeds N LEGACY duplicate unexplained rows (minted with the retired {@code perEventIdentity},
 * distinct trailIds, one shared cascade signature) plus their FK children, re-executes the V4
 * migration SQL (Part B is idempotent, guarded by {@code HAVING count(*) > 1}), and asserts:
 *   - exactly ONE row survives for the signature, re-keyed to signatureIdentity;
 *   - occurrence_count = SUM, instance_count = SUM, trail_count = distinct trails,
 *     first_seen = MIN(created_at), last_seen = MAX(updated_at);
 *   - the survivor's sample alarms are retained (fold-keeps-first);
 *   - FK children (contributing_event, pattern_trail, supporting_instance) are preserved/merged;
 *   - a SECOND run is a no-op (idempotent).
 *
 * <p>{@code @Tag("integration")}: run via {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class V4CollapseMigrationIT {

    private static final UUID NAMESPACE = UUID.fromString("6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f");

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

    @Autowired private DataSource dataSource;

    private static final List<String> SEQ = List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded");
    private static final String DOMAIN = "core-ip";
    private static final String SNAP = "snap-collapse";

    @Test
    void collapsesDuplicatesWithCorrectAggregates() throws Exception {
        // N=4 legacy duplicate rows of one signature across 3 distinct trails (trail-0 shared twice).
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        int n = 4;
        UUID[] ids = new UUID[n];
        String[] trails = {"trail-0" + uniq, "trail-0" + uniq, "trail-1" + uniq, "trail-2" + uniq};
        int[] occ = {1, 2, 1, 3};
        int[] inst = {5, 10, 7, 8};
        double[] support = {0.4, 0.6, 0.5, 0.9};

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int i = 0; i < n; i++) {
                UUID pid = UuidV5.perEventIdentity(trails[i], SEQ, "w" + i + uniq, snap);
                ids[i] = pid;
                // created_at increasing so row 0 is the earliest -> survivor.
                OffsetDateTime createdAt = OffsetDateTime.parse("2026-06-0" + (i + 1) + "T00:00:00Z");
                OffsetDateTime updatedAt = OffsetDateTime.parse("2026-06-1" + (i + 1) + "T00:00:00Z");
                insertLegacyPattern(c, pid, trails[i], snap, occ[i], inst[i], support[i],
                        createdAt, updatedAt);
                insertSequence(c, pid, SEQ);
                insertContributingEvent(c, UUID.randomUUID(), pid, inst[i]);
                insertPatternTrail(c, pid, trails[i], createdAt);
                insertSupportingInstance(c, pid, "sw-" + i + uniq, snap);
            }
            // Survivor (row 0) owns the sample the collapse must keep.
            insertSampleAlarm(c, ids[0], "keep-alarm" + uniq);
            insertSampleAlarm(c, ids[1], "loser-alarm" + uniq);
        }

        runV4();

        UUID newId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);
        // Exactly one row survives, re-keyed to signatureIdentity.
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(newId)).isTrue();

        // occurrence_count = SUM(1,2,1,3)=7; instance_count = SUM(5,10,7,8)=30.
        assertThat(intCol(newId, "occurrence_count")).isEqualTo(7);
        assertThat(intCol(newId, "instance_count")).isEqualTo(30);
        // distinct trails: trail-0, trail-1, trail-2 -> 3.
        assertThat(intCol(newId, "trail_count")).isEqualTo(3);
        assertThat(trailCountTable(newId)).isEqualTo(3);
        // instance-weighted support: (0.4*5+0.6*10+0.5*7+0.9*8)/30 = (2+6+3.5+7.2)/30 = 0.623333..
        assertThat(doubleCol(newId, "support")).isCloseTo((0.4 * 5 + 0.6 * 10 + 0.5 * 7 + 0.9 * 8) / 30, within(1e-9));
        // first_seen = MIN(created_at) = 2026-06-01; last_seen = MAX(updated_at) = 2026-06-14.
        assertThat(tsCol(newId, "first_seen")).isEqualTo(OffsetDateTime.parse("2026-06-01T00:00:00Z"));
        assertThat(tsCol(newId, "last_seen")).isEqualTo(OffsetDateTime.parse("2026-06-14T00:00:00Z"));
        // Survivor's sample kept, loser's discarded.
        assertThat(sampleAlarmIds(newId)).containsExactly("keep-alarm" + uniq);
        // All 4 contributing events preserved (re-pointed onto the survivor).
        assertThat(contributingCount(newId)).isEqualTo(4);
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int i = 0; i < 3; i++) {
                UUID pid = UuidV5.perEventIdentity("trail-" + i + uniq, SEQ, "w" + i + uniq, snap);
                OffsetDateTime at = OffsetDateTime.parse("2026-06-0" + (i + 1) + "T00:00:00Z");
                insertLegacyPattern(c, pid, "trail-" + i + uniq, snap, 1, 4, 0.5, at, at);
                insertSequence(c, pid, SEQ);
                insertContributingEvent(c, UUID.randomUUID(), pid, 4);
                insertPatternTrail(c, pid, "trail-" + i + uniq, at);
            }
        }

        runV4();
        UUID newId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);
        int occAfterFirst = intCol(newId, "occurrence_count");
        int trailAfterFirst = intCol(newId, "trail_count");
        assertThat(countRowsForSnap(snap)).isEqualTo(1);

        // Second run must be a no-op (HAVING count(*) > 1 selects nothing).
        runV4();

        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(intCol(newId, "occurrence_count")).isEqualTo(occAfterFirst);
        assertThat(intCol(newId, "trail_count")).isEqualTo(trailAfterFirst);
    }

    // --- V4 execution + seeding helpers ---

    private void runV4() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V4__signature_fold.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String seqCsv(List<String> seq) {
        return String.join(",", seq);
    }

    private void insertLegacyPattern(Connection c, UUID pid, String trailId, String snap, int occ,
            int inst, double support, OffsetDateTime createdAt, OffsetDateTime updatedAt) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO pattern.pattern
                    (pattern_id, trail_id, root_cause_alarm_type, support, confidence, lift, timing,
                     reconcile_status, structurally_validated, session_window_ms, session_window_type,
                     instance_count, occurrence_count, trail_count, first_seen, last_seen, lifecycle,
                     domain, snapshot_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, 'unexplained', true, 20000, 'gap-based',
                        ?, ?, 1, ?, ?, 'draft', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, pid);
            ps.setString(2, trailId);
            ps.setString(3, SEQ.get(0));
            ps.setDouble(4, support);
            ps.setDouble(5, 0.7);
            ps.setDouble(6, 2.0);
            ps.setInt(7, inst);
            ps.setInt(8, occ);
            ps.setObject(9, createdAt);
            ps.setObject(10, updatedAt);
            ps.setString(11, DOMAIN);
            ps.setString(12, snap);
            ps.setObject(13, createdAt);
            ps.setObject(14, updatedAt);
            ps.executeUpdate();
        }
    }

    private void insertSequence(Connection c, UUID pid, List<String> seq) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pattern.sequence_element (id, pattern_id, position, alarm_type, optional) "
                        + "VALUES (?, ?, ?, ?, false)")) {
            for (int i = 0; i < seq.size(); i++) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, pid);
                ps.setInt(3, i);
                ps.setString(4, seq.get(i));
                ps.executeUpdate();
            }
        }
    }

    private void insertContributingEvent(Connection c, UUID eventId, UUID pid, int occ) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pattern.contributing_event (event_id, pattern_id, anchor_scenario_id, "
                        + "occurrences, support, folded_at) VALUES (?, ?, NULL, ?, 0.5, now())")) {
            ps.setObject(1, eventId);
            ps.setObject(2, pid);
            ps.setInt(3, occ);
            ps.executeUpdate();
        }
    }

    private void insertPatternTrail(Connection c, UUID pid, String trailId, OffsetDateTime at) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pattern.pattern_trail (pattern_id, trail_id, first_seen) VALUES (?, ?, ?) "
                        + "ON CONFLICT (pattern_id, trail_id) DO NOTHING")) {
            ps.setObject(1, pid);
            ps.setString(2, trailId);
            ps.setObject(3, at);
            ps.executeUpdate();
        }
    }

    private void insertSupportingInstance(Connection c, UUID pid, String windowId, String snap) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pattern.supporting_instance (id, pattern_id, source_window_id, snapshot_id, "
                        + "occurrence) VALUES (?, ?, ?, ?, NULL)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, pid);
            ps.setString(3, windowId);
            ps.setString(4, snap);
            ps.executeUpdate();
        }
    }

    private void insertSampleAlarm(Connection c, UUID pid, String alarmId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pattern.sample_alarm (id, pattern_id, alarm_id, alarm_type, raised_at, "
                        + "managed_object_id, perceived_severity, position) "
                        + "VALUES (?, ?, ?, 'FiberFault', now(), 'OpticalPort:n1', 'major', 0)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, pid);
            ps.setString(3, alarmId);
            ps.executeUpdate();
        }
    }

    // --- assertions helpers ---

    private UUID signatureIdSql(String name) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT pattern.uuid_v5(?::uuid, ?)")) {
            ps.setString(1, NAMESPACE.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private int countRowsForSnap(String snap) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pattern.pattern WHERE snapshot_id = ? AND anchor_scenario_id IS NULL")) {
            ps.setString(1, snap);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean rowExists(UUID pid) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pattern.pattern WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    private int intCol(UUID pid, String col) throws Exception {
        return (int) longCol(pid, col);
    }

    private long longCol(UUID pid, String col) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT " + col + " FROM pattern.pattern WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private double doubleCol(UUID pid, String col) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT " + col + " FROM pattern.pattern WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        }
    }

    private OffsetDateTime tsCol(UUID pid, String col) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT " + col + " FROM pattern.pattern WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class).withOffsetSameInstant(java.time.ZoneOffset.UTC);
            }
        }
    }

    private int trailCountTable(UUID pid) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pattern.pattern_trail WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contributingCount(UUID pid) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pattern.contributing_event WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> sampleAlarmIds(UUID pid) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT alarm_id FROM pattern.sample_alarm WHERE pattern_id = ? ORDER BY position")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }
}
