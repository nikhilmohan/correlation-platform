package com.acp.alarmmanager.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response of {@code POST /admin/purge-live-alarms} — the demo/ops reset of the P3 live-alarm
 * path. Each field is the number of rows deleted from the corresponding {@code live_alarm} table
 * (the Alarm Manager's OWN schema; no other schema is touched). Idempotent: a second call on an
 * already-empty store returns all zeros with 200.
 *
 * @param purgedAlarms         rows deleted from {@code live_alarm.alarm}
 * @param purgedTransitions    rows deleted from {@code live_alarm.state_transition}
 * @param purgedPendingStatus  rows deleted from {@code live_alarm.pending_status}
 * @param purgedProcessedEvents rows deleted from {@code live_alarm.processed_event}
 */
@Schema(description = "Counts of rows deleted per live_alarm table by the P3 live-state purge.")
public record PurgeSummary(
        int purgedAlarms,
        int purgedTransitions,
        int purgedPendingStatus,
        int purgedProcessedEvents) {
}
