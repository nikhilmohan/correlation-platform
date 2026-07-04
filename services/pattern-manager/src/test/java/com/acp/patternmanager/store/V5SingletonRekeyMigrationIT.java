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
 * [SIG-FOLD-V5] Real-Postgres integration test of the V5 singleton-rekey migration -- the coverage
 * that V4's ITs missed. V4's Part B only collapsed DUPLICATE unexplained groups
 * ({@code HAVING count(*) > 1}); pre-existing SINGLETON unexplained rows kept their stale
 * {@code perEventIdentity} id and later duplicated when a fresh occurrence arrived. V5 re-keys EVERY
 * remaining unexplained row to {@code signatureIdentity}.
 *
 * <p>Reuses the {@link V4CollapseMigrationIT} harness/seeding pattern. Cases:
 * <ul>
 *   <li>a pre-existing SINGLETON stale-id row, no signature row yet -> re-keyed to
 *       signatureIdentity (so a subsequent fresh occurrence FOLDS in rather than duplicating);</li>
 *   <li>the live dup case -- a stale-id singleton + a signatureIdentity row for the SAME signature
 *       -> V5 MERGES them into ONE row at signatureIdentity (summed occurrence/instance, unioned
 *       trails, min/max seen, survivor's sample_alarms);</li>
 *   <li>idempotency -- running V5 twice makes no further change;</li>
 *   <li>V4-already-collapsed rows are untouched (no-op);</li>
 *   <li>whole-store: 0 duplicates by identity + every unexplained pattern_id == uuid_v5 of its own
 *       signature name-string.</li>
 * </ul>
 *
 * <p>{@code @Tag("integration")}: run via {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class V5SingletonRekeyMigrationIT {

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

    private static final List<String> SEQ =
            List.of("OSPFAdjacencyDown", "AdjDown", "BGPPeerDown", "RouteFlap", "LDPSessionDown");
    private static final String DOMAIN = "core-ip";
    private static final String SNAP = "snap-v5";

    /**
     * Case 1: a pre-existing SINGLETON unexplained row minted with the retired perEventIdentity id
     * (NOT signatureIdentity) and NO signature row yet. After V5 the SAME single row's metrics remain
     * but its pattern_id == signatureIdentity, so a subsequent fresh occurrence with the same
     * signature folds into it rather than duplicating.
     */
    @Test
    void reKeysPreExistingSingletonToSignatureIdentity() throws Exception {
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        String trail = "trail-solo" + uniq;
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-06-05T00:00:00Z");

        UUID staleId = UuidV5.perEventIdentity(trail, SEQ, "w0" + uniq, snap);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            insertLegacyPattern(c, staleId, trail, snap, 1, 6, 0.5, createdAt, updatedAt);
            insertSequence(c, staleId, SEQ);
            insertContributingEvent(c, UUID.randomUUID(), staleId, 6);
            insertPatternTrail(c, staleId, trail, createdAt);
            insertSupportingInstance(c, staleId, "sw0" + uniq, snap);
            insertSampleAlarm(c, staleId, "solo-alarm" + uniq);
        }

        UUID sigId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);
        // Precondition: the stale id is NOT the signature id (V4 gap: it was left as perEventIdentity).
        assertThat(staleId).isNotEqualTo(sigId);

        runV5();

        // Exactly one row, now at signatureIdentity; stale id is gone.
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(sigId)).isTrue();
        assertThat(rowExists(staleId)).isFalse();
        // Metrics carried over unchanged (single row).
        assertThat(intCol(sigId, "occurrence_count")).isEqualTo(1);
        assertThat(intCol(sigId, "instance_count")).isEqualTo(6);
        assertThat(intCol(sigId, "trail_count")).isEqualTo(1);
        assertThat(tsCol(sigId, "first_seen")).isEqualTo(createdAt);
        assertThat(tsCol(sigId, "last_seen")).isEqualTo(updatedAt);
        // Children re-pointed; survivor sample kept.
        assertThat(contributingCount(sigId)).isEqualTo(1);
        assertThat(sampleAlarmIds(sigId)).containsExactly("solo-alarm" + uniq);
        assertThat(trailCountTable(sigId)).isEqualTo(1);
    }

    /**
     * Case 2 (the LIVE dup case): a pre-existing SINGLETON stale-id row PLUS a fresh
     * signatureIdentity row for the SAME signature. V5 folds the stale row into the existing
     * signature survivor -> ONE row at signatureIdentity with summed occurrence/instance, unioned
     * trails, min/max seen, survivor's sample alarms.
     */
    @Test
    void mergesStaleSingletonIntoExistingSignatureRow() throws Exception {
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        UUID sigId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);

        String staleTrail = "trail-stale" + uniq;
        String freshTrail = "trail-fresh" + uniq;
        UUID staleId = UuidV5.perEventIdentity(staleTrail, SEQ, "w-stale" + uniq, snap);

        OffsetDateTime staleCreated = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        OffsetDateTime staleUpdated = OffsetDateTime.parse("2026-06-03T00:00:00Z");
        OffsetDateTime freshCreated = OffsetDateTime.parse("2026-06-02T00:00:00Z");
        OffsetDateTime freshUpdated = OffsetDateTime.parse("2026-06-10T00:00:00Z");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            // Stale-id singleton (occ 2, inst 5, support 0.4).
            insertLegacyPattern(c, staleId, staleTrail, snap, 2, 5, 0.4, staleCreated, staleUpdated);
            insertSequence(c, staleId, SEQ);
            insertContributingEvent(c, UUID.randomUUID(), staleId, 2);
            insertPatternTrail(c, staleId, staleTrail, staleCreated);
            insertSupportingInstance(c, staleId, "sw-stale" + uniq, snap);
            insertSampleAlarm(c, staleId, "stale-alarm" + uniq);

            // Fresh signatureIdentity row (occ 3, inst 10, support 0.8) -- the runtime survivor.
            insertLegacyPattern(c, sigId, freshTrail, snap, 3, 10, 0.8, freshCreated, freshUpdated);
            insertSequence(c, sigId, SEQ);
            insertContributingEvent(c, UUID.randomUUID(), sigId, 3);
            insertPatternTrail(c, sigId, freshTrail, freshCreated);
            insertSupportingInstance(c, sigId, "sw-fresh" + uniq, snap);
            insertSampleAlarm(c, sigId, "fresh-alarm" + uniq);
        }

        runV5();

        // ONE row survives at signatureIdentity; stale id gone.
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(sigId)).isTrue();
        assertThat(rowExists(staleId)).isFalse();
        // occurrence = 2+3 = 5; instance = 5+10 = 15.
        assertThat(intCol(sigId, "occurrence_count")).isEqualTo(5);
        assertThat(intCol(sigId, "instance_count")).isEqualTo(15);
        // distinct trails: stale + fresh -> 2.
        assertThat(intCol(sigId, "trail_count")).isEqualTo(2);
        assertThat(trailCountTable(sigId)).isEqualTo(2);
        // instance-weighted support: (0.4*5 + 0.8*10)/15 = (2 + 8)/15 = 0.6666..
        assertThat(doubleCol(sigId, "support"))
                .isCloseTo((0.4 * 5 + 0.8 * 10) / 15.0, within(1e-9));
        // first_seen = MIN(created) = fresh? no: stale 06-01 < fresh 06-02 -> 06-01.
        assertThat(tsCol(sigId, "first_seen")).isEqualTo(staleCreated);
        // last_seen = MAX(updated) = fresh 06-10.
        assertThat(tsCol(sigId, "last_seen")).isEqualTo(freshUpdated);
        // Fold-keeps-first: the survivor (existing signature row) keeps ITS sample alarm.
        assertThat(sampleAlarmIds(sigId)).containsExactly("fresh-alarm" + uniq);
        // Both contributing events preserved.
        assertThat(contributingCount(sigId)).isEqualTo(2);
    }

    /** Idempotency: running V5 twice makes no further change. */
    @Test
    void migrationIsIdempotent() throws Exception {
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        String trail = "trail-idem" + uniq;
        OffsetDateTime at = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        UUID staleId = UuidV5.perEventIdentity(trail, SEQ, "w-idem" + uniq, snap);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            insertLegacyPattern(c, staleId, trail, snap, 1, 4, 0.5, at, at);
            insertSequence(c, staleId, SEQ);
            insertContributingEvent(c, UUID.randomUUID(), staleId, 4);
            insertPatternTrail(c, staleId, trail, at);
        }

        runV5();
        UUID sigId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);
        int occ = intCol(sigId, "occurrence_count");
        int trailCount = intCol(sigId, "trail_count");
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(sigId)).isTrue();

        // Second run: the sole row is already at signatureIdentity -> no-op.
        runV5();

        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(sigId)).isTrue();
        assertThat(intCol(sigId, "occurrence_count")).isEqualTo(occ);
        assertThat(intCol(sigId, "trail_count")).isEqualTo(trailCount);
    }

    /**
     * V5 is a no-op on a store where V4 already collapsed the dup group: the collapsed row is already
     * at signatureIdentity (a 1-row group) -> V5 leaves it untouched.
     */
    @Test
    void noOpOnAlreadyCollapsedRow() throws Exception {
        String uniq = "-" + UUID.randomUUID();
        String snap = SNAP + uniq;
        // Seed 2 stale duplicates + run V4 (its HAVING count(*) > 1 collapses them).
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int i = 0; i < 2; i++) {
                String trail = "trail-" + i + uniq;
                UUID pid = UuidV5.perEventIdentity(trail, SEQ, "w" + i + uniq, snap);
                OffsetDateTime at = OffsetDateTime.parse("2026-06-0" + (i + 1) + "T00:00:00Z");
                insertLegacyPattern(c, pid, trail, snap, 1, 4, 0.5, at, at);
                insertSequence(c, pid, SEQ);
                insertContributingEvent(c, UUID.randomUUID(), pid, 4);
                insertPatternTrail(c, pid, trail, at);
            }
        }
        runV4();
        UUID sigId = signatureIdSql(seqCsv(SEQ) + "|" + DOMAIN + "|" + snap);
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        int occBefore = intCol(sigId, "occurrence_count");
        int trailBefore = intCol(sigId, "trail_count");

        runV5();

        // Untouched.
        assertThat(countRowsForSnap(snap)).isEqualTo(1);
        assertThat(rowExists(sigId)).isTrue();
        assertThat(intCol(sigId, "occurrence_count")).isEqualTo(occBefore);
        assertThat(intCol(sigId, "trail_count")).isEqualTo(trailBefore);
    }

    /**
     * Whole-store invariant across a mixed seeding (a re-key singleton + a merge pair): after V5,
     * among unexplained rows there are 0 duplicates by identity, and every unexplained pattern_id ==
     * uuid_v5 of its own signature name-string.
     */
    @Test
    void wholeStoreHasNoDuplicatesByIdentityAndEveryRowAtSignatureIdentity() throws Exception {
        String uniq = "-" + UUID.randomUUID();

        // Group A: lone stale singleton.
        String snapA = SNAP + "-A" + uniq;
        List<String> seqA = List.of("PortDown", "InterfaceDown", "CRCErrors", "InterfaceErrors");
        UUID staleA = UuidV5.perEventIdentity("trailA" + uniq, seqA, "wA" + uniq, snapA);

        // Group B: stale singleton + existing signature row (merge).
        String snapB = SNAP + "-B" + uniq;
        List<String> seqB = SEQ;
        UUID sigB = signatureIdSql(seqCsv(seqB) + "|" + DOMAIN + "|" + snapB);
        UUID staleB = UuidV5.perEventIdentity("trailB" + uniq, seqB, "wB" + uniq, snapB);

        OffsetDateTime at = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            insertLegacyPattern(c, staleA, "trailA" + uniq, snapA, 1, 3, 0.5, at, at);
            insertSequence(c, staleA, seqA);

            insertLegacyPattern(c, staleB, "trailB" + uniq, snapB, 1, 3, 0.5, at, at);
            insertSequence(c, staleB, seqB);
            insertLegacyPattern(c, sigB, "trailB2" + uniq, snapB, 1, 4, 0.6, at, at);
            insertSequence(c, sigB, seqB);
        }

        runV5();

        // For the two seeded snapshots: exactly one row each, at signatureIdentity, and each row's
        // pattern_id equals uuid_v5 of its own signature name-string.
        assertThat(countRowsForSnap(snapA)).isEqualTo(1);
        assertThat(countRowsForSnap(snapB)).isEqualTo(1);
        assertNoDuplicatesAndSelfConsistentForSnap(snapA);
        assertNoDuplicatesAndSelfConsistentForSnap(snapB);
    }

    // --- migration execution + seeding helpers (mirrors V4CollapseMigrationIT) ---

    private void runV5() throws Exception {
        runMigrationResource("/db/migration/V5__singleton_rekey.sql");
    }

    private void runV4() throws Exception {
        runMigrationResource("/db/migration/V4__signature_fold.sql");
    }

    private void runMigrationResource(String resource) throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
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
        // pattern_name is NOT NULL after V6 (already applied at Flyway startup); supply a placeholder
        // for this legacy-row seeding (its exact value is irrelevant to the V5 rekey assertions).
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO pattern.pattern
                    (pattern_id, trail_id, root_cause_alarm_type, pattern_name, support, confidence,
                     lift, timing, reconcile_status, structurally_validated, session_window_ms,
                     session_window_type, instance_count, occurrence_count, trail_count, first_seen,
                     last_seen, lifecycle, domain, snapshot_id, created_at, updated_at)
                VALUES (?, ?, ?, 'root Cascade · seeded01', ?, ?, ?, '{}'::jsonb, 'unexplained', true,
                        20000, 'gap-based', ?, ?, 1, ?, ?, 'draft', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, pid);
            ps.setString(2, trailId);
            ps.setString(3, "root");
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

    // --- assertion helpers ---

    private void assertNoDuplicatesAndSelfConsistentForSnap(String snap) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("""
                        SELECT p.pattern_id,
                               pattern.uuid_v5(
                                   '6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f'::uuid,
                                   COALESCE(se.seq_csv, '') || '|' || COALESCE(p.domain, '')
                                       || '|' || COALESCE(p.snapshot_id, '')) AS sig_id
                        FROM pattern.pattern p
                        LEFT JOIN (
                            SELECT pattern_id, string_agg(alarm_type, ',' ORDER BY position) AS seq_csv
                            FROM pattern.sequence_element GROUP BY pattern_id
                        ) se ON se.pattern_id = p.pattern_id
                        WHERE p.snapshot_id = ? AND p.anchor_scenario_id IS NULL
                        """)) {
            ps.setString(1, snap);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.Set<String> ids = new java.util.HashSet<>();
                while (rs.next()) {
                    String pid = rs.getString("pattern_id");
                    String sig = rs.getString("sig_id");
                    // every unexplained pattern_id == uuid_v5 of its own signature name-string.
                    assertThat(pid).isEqualTo(sig);
                    // 0 duplicates by identity.
                    assertThat(ids.add(pid)).as("duplicate identity " + pid).isTrue();
                }
            }
        }
    }

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
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT " + col + " FROM pattern.pattern WHERE pattern_id = ?")) {
            ps.setObject(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
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
