package com.acp.patternmanager.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, representative sample of a real member alarm this pattern was mined from — for operator
 * XAI / trust (spec-sample-alarms AC-SA-1..7). Sourced from the (already-frozen on {@code main})
 * optional {@code PatternMinedEvent.sampleAlarms[]} field. Mirrors {@link SupportingInstance} as the
 * intake-layer value object; carries exactly the 5 fields present in {@code transactions.clean}
 * {@code alarms[]} at mining time — no enrichment, no lookup.
 *
 * @param alarmId unique alarm identifier of the member alarm
 * @param alarmType canonical alarm-type token (same join key as the pattern's {@code sequence[]})
 * @param raisedAt when the member alarm was raised (ISO-8601 UTC)
 * @param managedObjectId managed object the alarm was raised on ({@code <objectType>:<id>} scheme)
 * @param perceivedSeverity X.733 severity of the member alarm
 */
public record SampleAlarm(
        String alarmId,
        String alarmType,
        OffsetDateTime raisedAt,
        String managedObjectId,
        String perceivedSeverity) {

    /**
     * Best-effort parse of the optional {@code payload.sampleAlarms} array off the raw mined-event
     * payload node (mirrors how the consumer reads {@code provenance}/{@code timing} off JSON — DA-4).
     * Backward-compatible: absent / null / not-an-array → empty list (no sample rows, pattern still
     * persisted). A malformed entry (missing/blank required field, or an un-parseable {@code raisedAt})
     * is dropped best-effort (non-fatal; spec Error handling) rather than failing the whole event.
     *
     * @param payload the validated {@code PatternMinedEvent} payload node
     * @return the parsed samples in the miner's received order (may be empty; never null)
     */
    public static List<SampleAlarm> parse(JsonNode payload) {
        List<SampleAlarm> out = new ArrayList<>();
        if (payload == null) {
            return out;
        }
        JsonNode arr = payload.path("sampleAlarms");
        if (!arr.isArray()) {
            return out; // absent / null / not-an-array -> empty (backward-compat)
        }
        for (JsonNode n : arr) {
            SampleAlarm sa = fromNode(n);
            if (sa != null) {
                out.add(sa);
            }
        }
        return out;
    }

    private static SampleAlarm fromNode(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        String alarmId = text(n, "alarmId");
        String alarmType = text(n, "alarmType");
        String rawRaisedAt = text(n, "raisedAt");
        String managedObjectId = text(n, "managedObjectId");
        String perceivedSeverity = text(n, "perceivedSeverity");
        if (alarmId == null || alarmType == null || rawRaisedAt == null
                || managedObjectId == null || perceivedSeverity == null) {
            return null; // malformed entry — drop best-effort
        }
        OffsetDateTime raisedAt;
        try {
            raisedAt = OffsetDateTime.parse(rawRaisedAt);
        } catch (Exception ex) {
            return null; // un-parseable timestamp — drop best-effort
        }
        return new SampleAlarm(alarmId, alarmType, raisedAt, managedObjectId, perceivedSeverity);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
