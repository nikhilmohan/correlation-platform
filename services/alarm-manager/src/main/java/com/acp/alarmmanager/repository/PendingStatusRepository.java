package com.acp.alarmmanager.repository;

import com.acp.alarmmanager.domain.PendingStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The durable parked-status store ({@code live_alarm.pending_status}) — the ordering-race fix.
 * When a status change ({@code alarms.status.changed}) references an alarm that has not yet been
 * persisted on the ingest path, the change is PARKED here keyed on {@code alarm_id} instead of
 * being dropped; the ingest path re-applies and deletes it once the alarm is persisted.
 *
 * <p>One row per {@code alarm_id} (last-write-wins by {@code changed_at}): if several status
 * changes arrive before the alarm persists (e.g. {@code in-progress} then {@code correlated}), the
 * upsert keeps the LATEST by {@code changed_at}. The state machine is monotonic toward
 * {@code correlated}, so keeping the latest is correct. A {@code null} {@code changed_at} is
 * treated as oldest so an explicit timestamp always wins.
 */
@Repository
public class PendingStatusRepository {

    private static final RowMapper<PendingStatus> MAPPER = (rs, n) -> new PendingStatus(
            rs.getString("alarm_id"),
            rs.getString("new_status"),
            rs.getString("source"),
            rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant(),
            rs.getString("caused_by_event_id"),
            rs.getTimestamp("received_at").toInstant());

    private final JdbcTemplate jdbc;

    public PendingStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert a parked status keyed on {@code alarm_id}, last-write-wins by {@code changed_at}. On
     * conflict the incoming row replaces the existing one only when its {@code changed_at} is newer
     * (a {@code null} existing {@code changed_at} is treated as oldest, so any explicit timestamp
     * wins; two {@code null}s keep the incoming — the most recent arrival).
     */
    public void upsert(PendingStatus p) {
        jdbc.update("""
                INSERT INTO live_alarm.pending_status (
                  alarm_id, new_status, source, changed_at, caused_by_event_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (alarm_id) DO UPDATE SET
                  new_status         = EXCLUDED.new_status,
                  source             = EXCLUDED.source,
                  changed_at         = EXCLUDED.changed_at,
                  caused_by_event_id = EXCLUDED.caused_by_event_id,
                  received_at        = EXCLUDED.received_at
                WHERE COALESCE(EXCLUDED.changed_at, 'epoch'::timestamptz)
                      >= COALESCE(live_alarm.pending_status.changed_at, 'epoch'::timestamptz)
                """,
                p.alarmId(), p.newStatus(), p.source(), ts(p.changedAt()), p.causedByEventId(),
                ts(p.receivedAt()));
    }

    public Optional<PendingStatus> find(String alarmId) {
        List<PendingStatus> rows = jdbc.query(
                "SELECT * FROM live_alarm.pending_status WHERE alarm_id = ?", MAPPER, alarmId);
        return rows.stream().findFirst();
    }

    /** Delete the parked entry after it has been re-applied. Idempotent. */
    public void delete(String alarmId) {
        jdbc.update("DELETE FROM live_alarm.pending_status WHERE alarm_id = ?", alarmId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
