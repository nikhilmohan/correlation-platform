package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.ChatterEntry;
import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Known-chatter removal (spec criterion 5; design step 6). Drops an alarm whose
 * {@code (managedObjectId, eventType)} pair is on the resolved source's effective chatter list
 * (base YAML + overlay adds - overlay removes). The match key is exactly
 * {@code (managedObjectId, eventType)} — promotion provenance ({@code alarmType}) never affects the
 * match. Because the list is read live from the (atomically-swapped) registry, a promoted entry
 * filters on the very next alarm with no restart (criterion 19).
 */
@Component
public class ChatterStep {

    private final MeterRegistry meters;

    public ChatterStep(MeterRegistry meters) {
        this.meters = meters;
    }

    public StepResult apply(AlarmEvent alarm, Ruleset ruleset, Path path) {
        for (ChatterEntry entry : ruleset.filterParams().chatterList()) {
            if (matches(entry, alarm)) {
                meters.counter("filtered_total", "filter", "chatter", "source", ruleset.source())
                        .increment();
                return StepResult.drop("chatter");
            }
        }
        return StepResult.cont(alarm);
    }

    private static boolean matches(ChatterEntry entry, AlarmEvent alarm) {
        return entry.managedObjectId() != null
                && entry.managedObjectId().equals(alarm.getManagedObjectId())
                && entry.eventType() != null
                && entry.eventType().equals(alarm.getEventType());
    }
}
