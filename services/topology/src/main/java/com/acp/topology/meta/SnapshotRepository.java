package com.acp.topology.meta;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC ({@code JdbcTemplate}) over the {@code topology_meta.snapshot} table — the
 * system-of-record for the current/previous {@code snapshotId} pointers and the ingest audit.
 * Touches only the {@code topology_meta} schema.
 */
@Repository
public class SnapshotRepository {

    private static final RowMapper<SnapshotRecord> MAPPER = (rs, rowNum) -> new SnapshotRecord(
            rs.getString("snapshot_id"),
            rs.getString("change_type"),
            rs.getString("domain"),
            rs.getInt("file_schema_version"),
            rs.getInt("node_count"),
            rs.getInt("edge_count"),
            rs.getString("status"),
            rs.getString("producer_supplied_id"),
            rs.getTimestamp("ingested_at") == null ? null
                    : rs.getTimestamp("ingested_at").toInstant(),
            rs.getString("event_id"),
            rs.getString("trace_id"));

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SnapshotRecord r) {
        jdbc.update("""
                INSERT INTO topology_meta.snapshot
                  (snapshot_id, change_type, domain, file_schema_version, node_count, edge_count,
                   status, producer_supplied_id, ingested_at, event_id, trace_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                r.snapshotId(), r.changeType(), r.domain(), r.fileSchemaVersion(), r.nodeCount(),
                r.edgeCount(), r.status(), r.producerSuppliedId(),
                Timestamp.from(r.ingestedAt() == null ? Instant.now() : r.ingestedAt()),
                r.eventId(), r.traceId());
    }

    /** Demote the current snapshot for a domain to previous (if any). */
    public void demoteCurrentToPrevious(String domain) {
        jdbc.update("UPDATE topology_meta.snapshot SET status = 'previous' "
                + "WHERE domain = ? AND status = 'current'", domain);
    }

    /** @return the snapshotId of the current 'previous' row for a domain, if present. */
    public Optional<String> findPreviousSnapshotId(String domain) {
        List<String> ids = jdbc.query(
                "SELECT snapshot_id FROM topology_meta.snapshot WHERE domain = ? AND status = 'previous'",
                (rs, n) -> rs.getString("snapshot_id"), domain);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public void deleteBySnapshotId(String snapshotId) {
        jdbc.update("DELETE FROM topology_meta.snapshot WHERE snapshot_id = ?", snapshotId);
    }

    public void setEventId(String snapshotId, String eventId) {
        jdbc.update("UPDATE topology_meta.snapshot SET event_id = ? WHERE snapshot_id = ?",
                eventId, snapshotId);
    }

    public Optional<SnapshotRecord> findById(String snapshotId) {
        List<SnapshotRecord> rows = jdbc.query(
                "SELECT * FROM topology_meta.snapshot WHERE snapshot_id = ?", MAPPER, snapshotId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SnapshotRecord> findCurrent(String domain) {
        List<SnapshotRecord> rows = jdbc.query(
                "SELECT * FROM topology_meta.snapshot WHERE domain = ? AND status = 'current'",
                MAPPER, domain);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SnapshotRecord> findAnyCurrent() {
        List<SnapshotRecord> rows = jdbc.query(
                "SELECT * FROM topology_meta.snapshot WHERE status = 'current' "
                        + "ORDER BY ingested_at DESC LIMIT 1", MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SnapshotRecord> listByDomain(String domain) {
        return jdbc.query(
                "SELECT * FROM topology_meta.snapshot WHERE domain = ? ORDER BY ingested_at DESC",
                MAPPER, domain);
    }

    public List<String> allSnapshotIds() {
        return jdbc.query("SELECT snapshot_id FROM topology_meta.snapshot",
                (rs, n) -> rs.getString("snapshot_id"));
    }
}
