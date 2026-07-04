package com.acp.correlationengine.integration;

import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.eventmodel.generated.AlarmEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Maps a frozen {@link AlarmEvent} into the engine-facing {@link ObservedAlarm} (carrying only the
 * canonical {@code alarmType} join key + {@code raisedAt}) and exposes its {@code trailIds[]} for the
 * per-trail fan-out. Reused by the consumer and the unit tests so both interpret the payload
 * identically.
 */
public final class AlarmEventMapper {

    private AlarmEventMapper() {
    }

    public static ObservedAlarm toObserved(AlarmEvent event) {
        return new ObservedAlarm(
                event.getAlarmId(),
                event.getAlarmType(),
                parseEpochMs(event.getRaisedAt()));
    }

    public static List<String> trailIds(AlarmEvent event) {
        List<String> ids = event.getTrailIds();
        return ids == null ? List.of() : ids;
    }

    private static long parseEpochMs(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }
}
