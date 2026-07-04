package com.acp.alarmmanager;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import java.util.List;

/** Shared test fixtures: envelope JSON builders + a codec + record builders. */
public final class Fixtures {

    public static final EventCodec CODEC = new EventCodec();

    private Fixtures() {
    }

    /** A valid AlarmEvent envelope (state=raised) with the canonical alarmType join token. */
    public static String alarmEventJson(String eventId, String alarmId, String alarmType,
            String state, List<String> trailIds, String raisedAt) {
        StringBuilder trails = new StringBuilder("[");
        for (int i = 0; i < trailIds.size(); i++) {
            if (i > 0) {
                trails.append(',');
            }
            trails.append('"').append(trailIds.get(i)).append('"');
        }
        trails.append(']');
        return """
                {
                  "eventId": "%s",
                  "type": "AlarmEvent",
                  "schemaVersion": 1,
                  "occurredAt": "2026-06-13T09:00:00Z",
                  "source": "enrichment",
                  "traceId": "trace-%s",
                  "payload": {
                    "alarmId": "%s",
                    "managedObjectId": "Port:ne1-1-1",
                    "eventType": "communicationsAlarm",
                    "probableCause": "lossOfSignal",
                    "alarmType": "%s",
                    "perceivedSeverity": "critical",
                    "raisedAt": "%s",
                    "state": "%s",
                    "trailIds": %s
                  }
                }
                """.formatted(eventId, alarmId, alarmId, alarmType, raisedAt, state, trails);
    }

    public static String defaultAlarmEventJson() {
        return alarmEventJson("11111111-1111-4111-8111-111111111111", "ALM-0001", "PortDown",
                "raised", List.of("trail-77"), "2026-06-13T09:00:00Z");
    }

    public static TypedEnvelope<Object> alarmEnvelope(String alarmId, String alarmType,
            String state) {
        return CODEC.deserialize(alarmEventJson(
                "evt-" + alarmId, alarmId, alarmType, state, List.of("trail-77"),
                "2026-06-13T09:00:00Z"));
    }

    /** A valid AlarmStatusChange envelope. */
    public static String statusChangeJson(String eventId, String alarmId, String newStatus,
            String source, String changedAt) {
        return """
                {
                  "eventId": "%s",
                  "type": "AlarmStatusChange",
                  "schemaVersion": 1,
                  "occurredAt": "%s",
                  "source": "%s",
                  "traceId": "trace-status",
                  "payload": {
                    "alarmId": "%s",
                    "newStatus": "%s",
                    "source": "%s",
                    "changedAt": "%s"
                  }
                }
                """.formatted(eventId, changedAt, source, alarmId, newStatus, source, changedAt);
    }

    public static TypedEnvelope<Object> statusEnvelope(String eventId, String alarmId,
            String newStatus) {
        return CODEC.deserialize(statusChangeJson(eventId, alarmId, newStatus,
                "correlation-engine", "2026-06-13T09:05:00Z"));
    }

    /** A valid CorrelationResultEvent envelope. */
    public static String correlationJson(String eventId, String incidentId, String rootCause,
            List<String> children) {
        StringBuilder kids = new StringBuilder("[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                kids.append(',');
            }
            kids.append('"').append(children.get(i)).append('"');
        }
        kids.append(']');
        return """
                {
                  "eventId": "%s",
                  "type": "CorrelationResultEvent",
                  "schemaVersion": 1,
                  "occurredAt": "2026-06-13T09:04:00Z",
                  "source": "correlation-engine",
                  "traceId": "trace-result",
                  "payload": {
                    "incidentId": "%s",
                    "rootCauseAlarmId": "%s",
                    "childAlarmIds": %s,
                    "confidence": 0.91,
                    "trailId": "trail-77"
                  }
                }
                """.formatted(eventId, incidentId, rootCause, kids);
    }

    public static TypedEnvelope<Object> correlationEnvelope(String eventId, String incidentId,
            String rootCause, List<String> children) {
        return CODEC.deserialize(correlationJson(eventId, incidentId, rootCause, children));
    }

    public static AlarmRecord alarmRecord(String alarmId, LifecycleState state, Role role,
            String incidentId, List<String> trailIds) {
        Instant now = Instant.parse("2026-06-13T09:00:00Z");
        return new AlarmRecord(alarmId, "Port:ne1-1-1", "communicationsAlarm", "lossOfSignal",
                "PortDown", "critical", "raised", now, null, trailIds, null, state, role,
                incidentId, false, "{}", now, now);
    }
}
