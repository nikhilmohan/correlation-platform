package com.acp.alarmmanager.repository;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The live alarm store ({@code live_alarm.alarm}) — the Alarm Manager is the sole writer. Uses
 * schema-qualified SQL so all access is against {@code live_alarm}. Persist is an idempotent
 * upsert keyed on {@code alarm_id} ({@code INSERT ... ON CONFLICT (alarm_id) DO NOTHING}); the
 * STATE and ROLE channels update disjoint columns.
 */
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbc;
    private final AlarmRowMapper rowMapper = new AlarmRowMapper();

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent first-insert of an ingested alarm with lifecycle {@code open}.
     *
     * @return {@code true} iff a new row was inserted (so the caller writes the single ingest
     *     {@code open} audit entry and republishes); {@code false} if the alarm already existed
     *     (Kafka redelivery — no double persist).
     */
    public boolean insertIfAbsent(AlarmRecord a) {
        int inserted = jdbc.update("""
                INSERT INTO live_alarm.alarm (
                  alarm_id, managed_object_id, event_type, probable_cause, alarm_type,
                  perceived_severity, wire_state, raised_at, cleared_at, trail_ids, vendor_raw,
                  lifecycle_state, role, incident_id, published, raw_envelope, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (alarm_id) DO NOTHING
                """,
                a.alarmId(), a.managedObjectId(), a.eventType(), a.probableCause(), a.alarmType(),
                a.perceivedSeverity(), a.wireState(), ts(a.raisedAt()), ts(a.clearedAt()),
                a.trailIds() == null ? "[]" : toJsonArray(a.trailIds()), a.vendorRawJson(),
                a.lifecycleState().wire(), a.role().wire(), a.incidentId(), a.published(),
                a.rawEnvelope(), ts(a.createdAt()), ts(a.updatedAt()));
        return inserted == 1;
    }

    public Optional<AlarmRecord> findById(String alarmId) {
        List<AlarmRecord> rows = jdbc.query(
                "SELECT * FROM live_alarm.alarm WHERE alarm_id = ?", rowMapper, alarmId);
        return rows.stream().findFirst();
    }

    /** @return {@code true} iff the alarm exists and its {@code published} flag is still false. */
    public boolean isUnpublished(String alarmId) {
        Boolean pub = jdbc.query(
                "SELECT published FROM live_alarm.alarm WHERE alarm_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, alarmId);
        return Boolean.FALSE.equals(pub);
    }

    /**
     * Atomically flip {@code published} false to true.
     *
     * @return {@code true} iff this call performed the flip (so the caller is the single emitter);
     *     {@code false} if it was already published (redelivery — no double republish).
     */
    public boolean markPublished(String alarmId, Instant now) {
        int updated = jdbc.update("""
                UPDATE live_alarm.alarm SET published = true, updated_at = ?
                WHERE alarm_id = ? AND published = false
                """, ts(now), alarmId);
        return updated == 1;
    }

    /**
     * Roll the {@code published} guard back to false after a failed republish send, so a Kafka
     * redelivery re-attempts the emit (the send-failure lost-emit window is closed). Idempotent:
     * only affects a row that this emitter had just claimed.
     */
    public void unmarkPublished(String alarmId, Instant now) {
        jdbc.update("""
                UPDATE live_alarm.alarm SET published = false, updated_at = ?
                WHERE alarm_id = ? AND published = true
                """, ts(now), alarmId);
    }

    /** STATE channel: set lifecycle_state (and cleared_at when clearing). */
    public void updateLifecycleState(String alarmId, LifecycleState state, Instant clearedAt,
            Instant now) {
        if (clearedAt != null) {
            jdbc.update("""
                    UPDATE live_alarm.alarm SET lifecycle_state = ?, cleared_at = ?, updated_at = ?
                    WHERE alarm_id = ?
                    """, state.wire(), ts(clearedAt), ts(now), alarmId);
        } else {
            jdbc.update("""
                    UPDATE live_alarm.alarm SET lifecycle_state = ?, updated_at = ?
                    WHERE alarm_id = ?
                    """, state.wire(), ts(now), alarmId);
        }
    }

    /**
     * STATE channel (revert): return lifecycle to {@code open}, clearing only a <em>provisional</em>
     * in-progress role association while <em>preserving</em> a finalised role/{@code incidentId}.
     *
     * <p>Per the approved design (Design alternatives, "Role-clearing on revert", option (b)), a
     * {@code reverted-open} means <em>this</em> correlation instance expired without a match; a
     * previously completed {@code CorrelationResultEvent} remains a real fact about the alarm. A
     * finalised role is anchored by its {@code incident_id}: the ROLE channel
     * ({@code updateRoleAndIncident}) is the only writer of {@code role} and always sets
     * {@code incident_id} together, so {@code incident_id IS NOT NULL} marks a finalised
     * ({@code root-cause}/{@code child}) role that must survive the revert. When there is no
     * finalised linkage ({@code incident_id IS NULL}) the role is at most provisional and is reset
     * to {@code none}. Lifecycle STATE always returns to {@code open}.
     */
    public void revertToOpenClearingProvisionalRole(String alarmId, Instant now) {
        jdbc.update("""
                UPDATE live_alarm.alarm
                   SET lifecycle_state = 'open',
                       role = CASE WHEN incident_id IS NOT NULL THEN role ELSE 'none' END,
                       updated_at = ?
                 WHERE alarm_id = ?
                """, ts(now), alarmId);
    }

    /** ROLE channel: set role + incident_id only (never lifecycle_state). */
    public void updateRoleAndIncident(String alarmId, Role role, String incidentId, Instant now) {
        jdbc.update("""
                UPDATE live_alarm.alarm SET role = ?, incident_id = ?, updated_at = ?
                WHERE alarm_id = ?
                """, role.wire(), incidentId, ts(now), alarmId);
    }

    /**
     * Demo/ops reset: delete every row from {@code live_alarm.alarm} and return the number of rows
     * deleted. This is the PARENT table in the {@code state_transition -> alarm} FK, so the caller
     * MUST delete {@code state_transition} first (FK-safe order); the transactional purge service
     * enforces that ordering.
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM live_alarm.alarm");
    }

    public boolean exists(String alarmId) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM live_alarm.alarm WHERE alarm_id = ?", Integer.class, alarmId);
        return c != null && c > 0;
    }

    /**
     * Read the alarm's CURRENT {@code lifecycle_state} (STATE channel) without loading the whole
     * row. Used by the state-precedence guard so a stronger state (e.g. {@code correlated}) is never
     * downgraded by a later out-of-order status-sync event. {@link Optional#empty()} when the alarm
     * does not exist.
     */
    public Optional<LifecycleState> currentLifecycleState(String alarmId) {
        return jdbc.query("SELECT lifecycle_state FROM live_alarm.alarm WHERE alarm_id = ?",
                rs -> rs.next() ? Optional.of(LifecycleState.fromWire(rs.getString(1)))
                        : Optional.empty(),
                alarmId);
    }

    /** Filtered, paged list of alarm summaries. */
    public List<AlarmRecord> query(AlarmQueryFilter f) {
        StringBuilder sql = new StringBuilder("SELECT * FROM live_alarm.alarm WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(f, sql, args);
        sql.append(" ORDER BY raised_at DESC, alarm_id ASC LIMIT ? OFFSET ?");
        args.add(f.limit());
        args.add(f.offset());
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    /** Total count matching the filter (ignoring paging) — the {@code total} envelope field. */
    public long count(AlarmQueryFilter f) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM live_alarm.alarm WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(f, sql, args);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private void appendFilters(AlarmQueryFilter f, StringBuilder sql, List<Object> args) {
        if (f.state() != null) {
            sql.append(" AND lifecycle_state = ?");
            args.add(f.state().wire());
        }
        if (f.trailId() != null) {
            // jsonb array membership: trail_ids @> '["<trailId>"]'
            sql.append(" AND trail_ids @> ?::jsonb");
            args.add(toJsonArray(List.of(f.trailId())));
        }
        if (f.incidentId() != null) {
            sql.append(" AND incident_id = ?");
            args.add(f.incidentId());
        }
        if (f.from() != null) {
            sql.append(" AND raised_at >= ?");
            args.add(ts(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND raised_at <= ?");
            args.add(ts(f.to()));
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
