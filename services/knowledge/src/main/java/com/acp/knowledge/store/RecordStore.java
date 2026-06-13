package com.acp.knowledge.store;

import com.acp.knowledge.domain.KnowledgeRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence over the unified {@code knowledge.record} + {@code knowledge.record_version}
 * tables. One identical CRUD/versioning/{@code is_current} path for all eight record types
 * (the design's unified-table decision). All payloads are {@code jsonb}.
 */
@Repository
public class RecordStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RecordStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private final RowMapper<KnowledgeRecord> rowMapper = this::mapRow;

    private KnowledgeRecord mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        JsonNode payload;
        try {
            payload = mapper.readTree(rs.getString("payload"));
        } catch (Exception e) {
            throw new IllegalStateException("corrupt jsonb payload for "
                    + rs.getString("record_id"), e);
        }
        Timestamp ts = rs.getTimestamp("created_at");
        return new KnowledgeRecord(
                rs.getString("domain"),
                rs.getString("record_type"),
                rs.getString("record_id"),
                rs.getString("version"),
                rs.getBoolean("is_current"),
                payload,
                rs.getString("author"),
                ts == null ? null : ts.toInstant());
    }

    /** @return true if the (domain, recordType, recordId) identity row exists. */
    public boolean recordExists(String domain, String recordType, String recordId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record "
                        + "WHERE domain = :domain AND record_type = :rt AND record_id = :rid",
                params(domain, recordType, recordId), Integer.class);
        return count != null && count > 0;
    }

    /** Insert the stable identity row (idempotent — caller ensures it does not yet exist). */
    public void insertIdentity(String domain, String recordType, String recordId) {
        jdbc.update(
                "INSERT INTO knowledge.record (domain, record_type, record_id) "
                        + "VALUES (:domain, :rt, :rid)",
                params(domain, recordType, recordId));
    }

    /** @return the highest version number minted for a record (0 if none). */
    public int maxVersionNumber(String domain, String recordType, String recordId) {
        // version labels are v{n}; extract the integer suffix and take the max.
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(substring(version from 2) AS INTEGER)), 0) "
                        + "FROM knowledge.record_version "
                        + "WHERE domain = :domain AND record_type = :rt AND record_id = :rid",
                params(domain, recordType, recordId), Integer.class);
        return max == null ? 0 : max;
    }

    /** Clear the current pointer for a record (used before inserting a new current version). */
    public void clearCurrent(String domain, String recordType, String recordId) {
        jdbc.update(
                "UPDATE knowledge.record_version SET is_current = FALSE "
                        + "WHERE domain = :domain AND record_type = :rt AND record_id = :rid "
                        + "AND is_current = TRUE",
                params(domain, recordType, recordId));
    }

    /** Insert a new version row as the current version. */
    public void insertVersion(String domain, String recordType, String recordId, String version,
            JsonNode payload, String author) {
        MapSqlParameterSource p = params(domain, recordType, recordId)
                .addValue("version", version)
                .addValue("payload", toJsonb(payload))
                .addValue("author", author);
        jdbc.update(
                "INSERT INTO knowledge.record_version "
                        + "(domain, record_type, record_id, version, is_current, payload, author) "
                        + "VALUES (:domain, :rt, :rid, :version, TRUE, :payload, :author)",
                p);
    }

    /** @return the current version of a record, if present. */
    public Optional<KnowledgeRecord> findCurrent(String domain, String recordType,
            String recordId) {
        List<KnowledgeRecord> rows = jdbc.query(
                "SELECT * FROM knowledge.record_version "
                        + "WHERE domain = :domain AND record_type = :rt AND record_id = :rid "
                        + "AND is_current = TRUE",
                params(domain, recordType, recordId), rowMapper);
        return rows.stream().findFirst();
    }

    /** @return a specific pinned version of a record, if present. */
    public Optional<KnowledgeRecord> findVersion(String domain, String recordType,
            String recordId, String version) {
        MapSqlParameterSource p = params(domain, recordType, recordId).addValue("version", version);
        List<KnowledgeRecord> rows = jdbc.query(
                "SELECT * FROM knowledge.record_version "
                        + "WHERE domain = :domain AND record_type = :rt AND record_id = :rid "
                        + "AND version = :version",
                p, rowMapper);
        return rows.stream().findFirst();
    }

    /** @return all current records for a domain + record type (the consumer read path). */
    public List<KnowledgeRecord> listCurrent(String domain, String recordType) {
        return jdbc.query(
                "SELECT * FROM knowledge.record_version "
                        + "WHERE domain = :domain AND record_type = :rt AND is_current = TRUE "
                        + "ORDER BY record_id",
                new MapSqlParameterSource().addValue("domain", domain).addValue("rt", recordType),
                rowMapper);
    }

    /** @return true if any record exists for the domain (any type) — used for 404 on vocabulary. */
    public boolean domainExists(String domain) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record WHERE domain = :domain",
                new MapSqlParameterSource().addValue("domain", domain), Integer.class);
        return count != null && count > 0;
    }

    private static MapSqlParameterSource params(String domain, String recordType, String recordId) {
        return new MapSqlParameterSource()
                .addValue("domain", domain)
                .addValue("rt", recordType)
                .addValue("rid", recordId);
    }

    private PGobject toJsonb(JsonNode payload) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(payload));
            return pg;
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode jsonb payload", e);
        }
    }
}
