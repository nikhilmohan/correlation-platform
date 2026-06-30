package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Self-clear suppression using the per-source {@code selfClearHoldTime} (spec criterion 4; design
 * step 4).
 *
 * <p>A {@code raised} alarm is <b>held</b> (not emitted yet) for the source's hold-time. A matching
 * {@code cleared} arriving within the hold-time is a transient — both are suppressed, emit nothing.
 * If the hold-time elapses with no clear, the held {@code raised} is <b>released</b> back into the
 * downstream pipeline by {@link #releaseExpired(java.util.function.BiConsumer)} (driven by a
 * scheduled sweep). A clear arriving after the hold-time elapsed (the raise already released) is not
 * suppressed and passes through.
 *
 * <p>Because the hold-time is per source, the same transient is suppressed under a short-hold source
 * and emitted under a long-hold source (criterion 11).
 */
@Component
public class SelfClearStep {

    /** A held raise awaiting either a matching clear or hold-time expiry. */
    private record Held(AlarmEvent raise, Ruleset ruleset, Path path, Instant heldAt,
            String occurredAt, String traceId) {}

    private final ConcurrentHashMap<WindowKey, Held> held = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public SelfClearStep(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param occurredAt the input envelope {@code occurredAt} (carried through the hold so a
     *     released raise re-enters with a valid envelope)
     * @param traceId the input envelope {@code traceId} (carried through the hold for propagation)
     */
    public StepResult apply(AlarmEvent alarm, Ruleset ruleset, Path path, String occurredAt,
            String traceId) {
        WindowKey key = new WindowKey(path, ruleset.source(), alarm.getManagedObjectId(),
                alarm.getEventType());
        Duration hold = ruleset.filterParams().selfClearHoldTime();
        Instant now = clock.instant();

        if (alarm.getState() == AlarmEvent.State.RAISED) {
            // Hold the raise; do not emit yet.
            held.put(key, new Held(alarm, ruleset, path, now, occurredAt, traceId));
            return StepResult.drop("self_clear_hold");
        }

        // A clear: is there a held raise still within the hold-time?
        Held h = held.get(key);
        if (h != null && !now.isAfter(h.heldAt().plus(hold))) {
            held.remove(key);
            meters.counter("filtered_total", "filter", "self_clear", "source", ruleset.source())
                    .increment();
            return StepResult.drop("self_clear");
        }
        // No held raise (already released) or clear is past hold-time: let the clear flow.
        return StepResult.cont(alarm);
    }

    /**
     * Release every held raise whose hold-time has elapsed (the transient never cleared) back into
     * the downstream pipeline. Invoked by a scheduled sweep on the same instant {@link Clock}.
     *
     * @param sink receives {@code (releasedRaise, ruleset, path)} for each expired hold
     */
    public void releaseExpired(ReleaseSink sink) {
        Instant now = clock.instant();
        List<WindowKey> ready = new ArrayList<>();
        for (var e : held.entrySet()) {
            Held h = e.getValue();
            if (now.isAfter(h.heldAt().plus(h.ruleset().filterParams().selfClearHoldTime()))) {
                ready.add(e.getKey());
            }
        }
        for (WindowKey key : ready) {
            Held h = held.remove(key);
            if (h != null) {
                sink.release(h.raise(), h.ruleset(), h.path(), h.occurredAt(), h.traceId());
            }
        }
    }

    /** Callback for a released (un-cleared) held raise re-entering the pipeline after FlapDamp. */
    @FunctionalInterface
    public interface ReleaseSink {
        void release(AlarmEvent raise, Ruleset ruleset, Path path, String occurredAt,
                String traceId);
    }
}
