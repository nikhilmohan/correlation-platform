package com.acp.correlationengine.incident;

import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL {@link IncidentRepository} over the owned {@code incident} schema
 * ({@code incident.incident} + {@code incident.incident_alarm}). Persistence is idempotent on
 * {@code instance_fingerprint} (the {@code UNIQUE} constraint makes a re-persist a no-op — AC16).
 * All external access to the Incident Store goes through this repository; no other service reads or
 * writes the schema directly.
 */
public class JdbcIncidentRepository implements IncidentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIncidentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean save(Incident incident) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("incidentId", incident.incidentId())
                .addValue("trailId", incident.trailId())
                .addValue("discoveryTrailId", incident.discoveryTrailId())
                .addValue("rootCauseAlarmId", incident.rootCauseAlarmId())
                .addValue("rootCauseAlarmType", incident.rootCauseAlarmType())
                .addValue("matchedPatternId", incident.matchedPatternId())
                .addValue("matchedCodebookId", incident.matchedCodebookId())
                .addValue("confidence", incident.confidence())
                .addValue("matchType", incident.matchTypeToken())
                .addValue("fingerprint", incident.instanceFingerprint())
                .addValue("createdAt", Timestamp.from(incident.createdAt()));
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO incident.incident
                        (incident_id, trail_id, discovery_trail_id, root_cause_alarm_id,
                         root_cause_alarm_type, matched_pattern_id, matched_codebook_id,
                         confidence, match_type, instance_fingerprint, created_at)
                    VALUES
                        (:incidentId, :trailId, :discoveryTrailId, :rootCauseAlarmId,
                         :rootCauseAlarmType, :matchedPatternId, :matchedCodebookId,
                         :confidence, :matchType, :fingerprint, :createdAt)
                    ON CONFLICT (instance_fingerprint) DO NOTHING
                    """, p);
        } catch (DuplicateKeyException e) {
            return false; // primary-key race — already persisted
        }
        if (inserted == 0) {
            return false; // duplicate fingerprint — idempotent no-op (AC16)
        }
        insertMembership(incident);
        return true;
    }

    private void insertMembership(Incident incident) {
        MapSqlParameterSource root = new MapSqlParameterSource()
                .addValue("incidentId", incident.incidentId())
                .addValue("alarmId", incident.rootCauseAlarmId())
                .addValue("role", "root_cause");
        jdbc.update(memberSql(), root);
        for (String child : incident.childAlarmIds()) {
            MapSqlParameterSource c = new MapSqlParameterSource()
                    .addValue("incidentId", incident.incidentId())
                    .addValue("alarmId", child)
                    .addValue("role", "child");
            jdbc.update(memberSql(), c);
        }
    }

    private static String memberSql() {
        return """
                INSERT INTO incident.incident_alarm (incident_id, alarm_id, role)
                VALUES (:incidentId, :alarmId, :role)
                ON CONFLICT (incident_id, alarm_id) DO NOTHING
                """;
    }

    @Override
    public Optional<Incident> findById(String incidentId) {
        List<Incident> rows = jdbc.query(
                "SELECT * FROM incident.incident WHERE incident_id = :id",
                new MapSqlParameterSource("id", incidentId),
                (rs, n) -> mapRow(rs));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withChildren(rows.get(0)));
    }

    @Override
    public List<Incident> find(IncidentFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM incident.incident WHERE 1=1");
        MapSqlParameterSource p = whereClause(sql, filter);
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        p.addValue("limit", Math.max(0, filter.limit()));
        p.addValue("offset", Math.max(0, filter.offset()));
        List<Incident> rows = jdbc.query(sql.toString(), p, (rs, n) -> mapRow(rs));
        List<Incident> out = new ArrayList<>(rows.size());
        for (Incident i : rows) {
            out.add(withChildren(i));
        }
        return out;
    }

    @Override
    public long count(IncidentFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM incident.incident WHERE 1=1");
        MapSqlParameterSource p = whereClause(sql, filter);
        Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
        return n == null ? 0L : n;
    }

    private static MapSqlParameterSource whereClause(StringBuilder sql, IncidentFilter filter) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (filter.trailId() != null) {
            sql.append(" AND trail_id = :trailId");
            p.addValue("trailId", filter.trailId());
        }
        if (filter.matchType() != null) {
            sql.append(" AND match_type = :matchType");
            p.addValue("matchType", filter.matchType());
        }
        if (filter.from() != null) {
            sql.append(" AND created_at >= :from");
            p.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND created_at <= :to");
            p.addValue("to", Timestamp.from(filter.to()));
        }
        return p;
    }

    @Override
    public long totalIncidents() {
        Long n = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM incident.incident", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public long countByMatchType(String matchTypeToken) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident.incident WHERE match_type = :mt",
                new MapSqlParameterSource("mt", matchTypeToken), Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public long distinctCorrelatedAlarmCount() {
        Long n = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(DISTINCT alarm_id) FROM incident.incident_alarm", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    @Transactional
    public PurgeCounts deleteAll() {
        // Transactional all-or-nothing purge of the CE-owned P3 incident state. Delete the child
        // membership rows first (ON DELETE CASCADE would also handle them, but deleting explicitly
        // yields the exact removed count for the response), then the incident rows. NOTE: we do NOT
        // touch incident.processed_event — that ledger holds patterns.approved / codebook.generated /
        // trails.built eventIds (the loaded P2 model) which must survive the reset.
        long purgedAlarms = jdbc.getJdbcTemplate()
                .update("DELETE FROM incident.incident_alarm");
        long purgedIncidents = jdbc.getJdbcTemplate()
                .update("DELETE FROM incident.incident");
        return new PurgeCounts(purgedIncidents, purgedAlarms);
    }

    @Override
    public Map<String, Long> confidenceDistribution() {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String b : List.of("0.0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1.0")) {
            buckets.put(b, 0L);
        }
        jdbc.getJdbcTemplate().query(
                "SELECT confidence FROM incident.incident",
                rs -> {
                    buckets.merge(InMemoryIncidentRepository.bucket(rs.getDouble("confidence")),
                            1L, Long::sum);
                });
        return buckets;
    }

    private Incident withChildren(Incident base) {
        List<String> children = jdbc.query(
                "SELECT alarm_id FROM incident.incident_alarm "
                        + "WHERE incident_id = :id AND role = 'child' ORDER BY id",
                new MapSqlParameterSource("id", base.incidentId()),
                (rs, n) -> rs.getString("alarm_id"));
        return new Incident(
                base.incidentId(), base.trailId(), base.discoveryTrailId(), base.rootCauseAlarmId(),
                base.rootCauseAlarmType(), children, base.matchedPatternId(), base.matchedCodebookId(),
                base.confidence(), base.matchType(), base.instanceFingerprint(), base.createdAt());
    }

    private static Incident mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String matchTypeToken = rs.getString("match_type");
        MatchCandidate.MatchType mt = "codebook".equals(matchTypeToken)
                ? MatchCandidate.MatchType.CODEBOOK : MatchCandidate.MatchType.PATTERN;
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new Incident(
                rs.getString("incident_id"),
                rs.getString("trail_id"),
                rs.getString("discovery_trail_id"),
                rs.getString("root_cause_alarm_id"),
                rs.getString("root_cause_alarm_type"),
                List.of(),
                rs.getString("matched_pattern_id"),
                rs.getString("matched_codebook_id"),
                rs.getDouble("confidence"),
                mt,
                rs.getString("instance_fingerprint"),
                createdAt == null ? Instant.now() : createdAt.toInstant());
    }
}
