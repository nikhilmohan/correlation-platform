package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.generated.AlarmEvent;
import com.acp.eventmodel.generated.VendorRaw;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Flap-damping using the per-source {@code flapN} / {@code flapWindow} (spec criterion 3; design
 * step 5). Counts raise/clear oscillations per key; when the count <b>exceeds</b> N within the
 * window the burst is collapsed into exactly one <b>summary</b> {@code AlarmEvent} and the rest of
 * the burst is suppressed (an oscillation of {@code N} or fewer is not damped).
 *
 * <p>Flap-summary shape (resolves design open question #40 — existing fields only): the summary
 * reuses the <b>first</b> oscillation's identity ({@code alarmId}, {@code raisedAt},
 * {@code perceivedSeverity}, {@code eventType}, {@code probableCause}, {@code alarmType},
 * {@code managedObjectId}), carries {@code state=raised}, and records {@code flapCount} +
 * {@code flapWindowSeconds} under {@code vendorRaw}. Deterministic/idempotent — re-running the same
 * burst yields the same summary id. No new top-level field, no contract change.
 */
@Component
public class FlapDampStep {

    private record Burst(AlarmEvent firstAlarm, Instant windowStart, int oscillations,
            boolean summarized) {}

    private final ConcurrentHashMap<WindowKey, Burst> bursts = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public FlapDampStep(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    public StepResult apply(AlarmEvent alarm, Ruleset ruleset, Path path) {
        WindowKey key = new WindowKey(path, ruleset.source(), alarm.getManagedObjectId(),
                alarm.getEventType());
        int n = ruleset.filterParams().flapN();
        Duration window = ruleset.filterParams().flapWindow();
        Instant now = clock.instant();

        Burst b = bursts.get(key);
        if (b == null || now.isAfter(b.windowStart().plus(window))) {
            // Fresh window: this alarm is the first oscillation, not yet flapping.
            bursts.put(key, new Burst(alarm, now, 1, false));
            return StepResult.cont(alarm);
        }

        int osc = b.oscillations() + 1;
        if (osc <= n) {
            // Still under threshold within the window: pass through normally.
            bursts.put(key, new Burst(b.firstAlarm(), b.windowStart(), osc, false));
            return StepResult.cont(alarm);
        }

        // Oscillation count now EXCEEDS N within the window → flapping.
        if (!b.summarized()) {
            // First time we cross the threshold: emit exactly one summary.
            bursts.put(key, new Burst(b.firstAlarm(), b.windowStart(), osc, true));
            meters.counter("filtered_total", "filter", "flap", "source", ruleset.source())
                    .increment();
            return StepResult.cont(summary(b.firstAlarm(), osc, window));
        }
        // Already summarized this burst: suppress the rest of the oscillation.
        bursts.put(key, new Burst(b.firstAlarm(), b.windowStart(), osc, true));
        meters.counter("filtered_total", "filter", "flap", "source", ruleset.source()).increment();
        return StepResult.drop("flap");
    }

    private static AlarmEvent summary(AlarmEvent first, int flapCount, Duration window) {
        AlarmEvent s = new AlarmEvent()
                .withAlarmId(first.getAlarmId())
                .withManagedObjectId(first.getManagedObjectId())
                .withEventType(first.getEventType())
                .withProbableCause(first.getProbableCause())
                .withAlarmType(first.getAlarmType())
                .withPerceivedSeverity(first.getPerceivedSeverity())
                .withRaisedAt(first.getRaisedAt())
                .withState(AlarmEvent.State.RAISED)
                .withTrailIds(new java.util.ArrayList<>());

        VendorRaw vr = new VendorRaw();
        if (first.getVendorRaw() != null) {
            vr.getAdditionalProperties().putAll(
                    new LinkedHashMap<>(first.getVendorRaw().getAdditionalProperties()));
        }
        vr.setAdditionalProperty("flapCount", flapCount);
        vr.setAdditionalProperty("flapWindowSeconds", window.toSeconds());
        s.setVendorRaw(vr);
        return s;
    }
}
