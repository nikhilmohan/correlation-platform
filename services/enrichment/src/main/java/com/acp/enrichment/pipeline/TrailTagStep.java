package com.acp.enrichment.pipeline;

import com.acp.enrichment.trail.TrailBuilderClient;
import com.acp.enrichment.trail.TrailLookupException;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * Trail-tags each survivor by calling the Trail Builder {@code getTrailsForObject} contract and
 * setting {@code AlarmEvent.trailIds} from the response {@code trailIds[]} (spec criteria 6, 17;
 * design step 7). On lookup failure after the configured retries it raises
 * {@link TrailLookupException} so the pipeline routes the alarm to the input DLQ — never emits with
 * empty {@code trailIds}, never drops silently (design open question #42).
 *
 * <p>Wired as a {@code @Bean} (not {@code @Component}) in {@code TrailBuilderConfig} because it needs
 * the configured {@code enrichment.domain} value injected.
 */
public class TrailTagStep {

    private final TrailBuilderClient client;
    private final String domain;
    private final MeterRegistry meters;

    public TrailTagStep(TrailBuilderClient client, String domain, MeterRegistry meters) {
        this.client = client;
        this.domain = domain;
        this.meters = meters;
    }

    /**
     * @param alarm the survivor alarm
     * @return the same alarm with {@code trailIds} populated from Trail Builder
     * @throws TrailLookupException if the lookup cannot be completed (routed to DLQ by the caller)
     */
    public AlarmEvent tag(AlarmEvent alarm) {
        try {
            List<String> trailIds = client.getTrailsForObject(alarm.getManagedObjectId(), domain);
            alarm.setTrailIds(trailIds == null ? new ArrayList<>() : new ArrayList<>(trailIds));
            return alarm;
        } catch (TrailLookupException e) {
            meters.counter("trail_lookup_failures_total").increment();
            throw e;
        } catch (RuntimeException e) {
            meters.counter("trail_lookup_failures_total").increment();
            throw new TrailLookupException(
                    "trail lookup failed for " + alarm.getManagedObjectId(), e);
        }
    }
}
