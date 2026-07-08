package com.acp.alarmmanager.repository;

import com.acp.alarmmanager.domain.StateTransitionRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The append-only audit ({@code live_alarm.state_transition}). One row per lifecycle/role change,
 * ordered by {@code occurred_at} for the transition history on {@code GET /alarms/{alarmId}}.
 */
@Repository
public class StateTransitionRepository {

    private static final RowMapper<StateTransitionRecord> MAPPER = (rs, n) -> new StateTransitionRecord(
            rs.getLong("id"),
            rs.getString("alarm_id"),
            rs.getString("to_state"),
            rs.getString("reason"),
            rs.getString("source"),
            rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant(),
            rs.getString("caused_by_event_id"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public StateTransitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append one audit entry. The partial unique index {@code uq_transition_open_ingest} makes the
     * ingest {@code open} entry at-most-once per alarm; on a redelivered ingest the duplicate is
     * swallowed (idempotent).
     */
    public void append(String alarmId, String toState, String reason, String source,
            Instant changedAt, String causedByEventId, Instant occurredAt) {
        try {
            jdbc.update("""
                    INSERT INTO live_alarm.state_transition (
                      alarm_id, to_state, reason, source, changed_at, caused_by_event_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, alarmId, toState, reason, source, ts(changedAt), causedByEventId,
                    ts(occurredAt));
        } catch (DuplicateKeyException dup) {
            // The at-most-one ingest-open guard fired on a redelivery — idempotent, ignore.
        }
    }

    /**
     * Demo/ops reset: delete every audit row from {@code live_alarm.state_transition} and return
     * the number of rows deleted. This is the CHILD side of the {@code state_transition -> alarm}
     * FK, so the transactional purge deletes it BEFORE {@code live_alarm.alarm}.
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM live_alarm.state_transition");
    }

    public List<StateTransitionRecord> findByAlarmOrdered(String alarmId) {
        return jdbc.query("""
                SELECT * FROM live_alarm.state_transition WHERE alarm_id = ?
                ORDER BY occurred_at ASC, id ASC
                """, MAPPER, alarmId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
