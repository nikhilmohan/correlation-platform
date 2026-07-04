package com.acp.alarmmanager.repository;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

/** Maps a {@code live_alarm.alarm} row to an {@link AlarmRecord}. */
public class AlarmRowMapper implements RowMapper<AlarmRecord> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Override
    public AlarmRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        List<String> trailIds;
        try {
            String trailJson = rs.getString("trail_ids");
            trailIds = trailJson == null ? List.of() : MAPPER.readValue(trailJson, STRING_LIST);
        } catch (Exception e) {
            throw new SQLException("failed to parse trail_ids", e);
        }
        return new AlarmRecord(
                rs.getString("alarm_id"),
                rs.getString("managed_object_id"),
                rs.getString("event_type"),
                rs.getString("probable_cause"),
                rs.getString("alarm_type"),
                rs.getString("perceived_severity"),
                rs.getString("wire_state"),
                toInstant(rs.getTimestamp("raised_at")),
                toInstant(rs.getTimestamp("cleared_at")),
                trailIds,
                rs.getString("vendor_raw"),
                LifecycleState.fromWire(rs.getString("lifecycle_state")),
                Role.fromWire(rs.getString("role")),
                rs.getString("incident_id"),
                rs.getBoolean("published"),
                rs.getString("raw_envelope"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
