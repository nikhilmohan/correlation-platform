package com.acp.patternmanager.api.dto;

import java.time.OffsetDateTime;

/**
 * A bounded, representative sample of a real member alarm a pattern was mined from, served on a
 * {@link PatternView} for operator XAI / trust (spec-sample-alarms OQ-SA-1). Carries exactly the 5
 * fields present in {@code transactions.clean} {@code alarms[]} at mining time — no enrichment.
 *
 * @param alarmId unique alarm identifier of the member alarm
 * @param alarmType canonical alarm-type token (a member of the pattern's {@code sequence[]})
 * @param raisedAt when the member alarm was raised (serialized ISO-8601 UTC)
 * @param managedObjectId managed object the alarm was raised on ({@code <objectType>:<id>} scheme)
 * @param perceivedSeverity X.733 severity of the member alarm
 */
public record SampleAlarmView(
        String alarmId,
        String alarmType,
        OffsetDateTime raisedAt,
        String managedObjectId,
        String perceivedSeverity) {
}
