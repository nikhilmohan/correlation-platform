package com.acp.alarmmanager.domain;

import java.time.Instant;
import java.util.List;

/**
 * A live alarm store row (the {@code live_alarm.alarm} table). Carries every required
 * {@code AlarmEvent} field — including the canonical {@code alarmType} join token in its own field,
 * distinct from {@code eventType} (X.733 category) and {@code probableCause} (X.733 probable
 * cause) — plus the Alarm-Manager-owned lifecycle STATE, denormalized correlation ROLE +
 * {@code incidentId}, and the republish-once {@code published} guard.
 */
public record AlarmRecord(
        String alarmId,
        String managedObjectId,
        String eventType,
        String probableCause,
        String alarmType,
        String perceivedSeverity,
        String wireState,
        Instant raisedAt,
        Instant clearedAt,
        List<String> trailIds,
        String vendorRawJson,
        LifecycleState lifecycleState,
        Role role,
        String incidentId,
        boolean published,
        String rawEnvelope,
        Instant createdAt,
        Instant updatedAt) {
}
