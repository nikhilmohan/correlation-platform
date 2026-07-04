package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a codec-bound {@link AlarmEvent} envelope to an {@link AlarmRecord} for persistence,
 * carrying every required field including the canonical {@code alarmType} join token into its own
 * field (distinct from {@code eventType}/{@code probableCause}), plus the exact consumed envelope
 * (so republish re-emits a faithful {@code AlarmEvent}).
 */
@Component
public class AlarmMapper {

    private final EventCodec codec;

    public AlarmMapper(EventCodec codec) {
        this.codec = codec;
    }

    /** Build a fresh {@code open} alarm record from an ingested {@code AlarmEvent}. */
    public AlarmRecord toOpenRecord(TypedEnvelope<Object> envelope, Instant now) {
        AlarmEvent a = (AlarmEvent) envelope.getPayload();
        String rawEnvelope = codec.serialize(envelope);
        String vendorRawJson = a.getVendorRaw() == null ? null
                : codec.objectMapper().valueToTree(a.getVendorRaw()).toString();
        return new AlarmRecord(
                a.getAlarmId(),
                a.getManagedObjectId(),
                a.getEventType(),
                a.getProbableCause(),
                a.getAlarmType(),
                a.getPerceivedSeverity(),
                a.getState() == null ? null : a.getState().value(),
                parseInstant(a.getRaisedAt()),
                parseInstant(a.getClearedAt()),
                a.getTrailIds() == null ? List.of() : a.getTrailIds(),
                vendorRawJson,
                LifecycleState.OPEN,
                Role.NONE,
                null,
                false,
                rawEnvelope,
                now,
                now);
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
