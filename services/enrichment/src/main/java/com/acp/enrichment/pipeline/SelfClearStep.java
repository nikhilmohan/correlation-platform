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
 *
 * <p><b>Event-time clear-matching (raisedAt, not wall-clock).</b> Whether a clear counts as
 * "self-clearing" a held raise is decided over the alarms' own logical time: the transient is
 * suppressed iff the clear's {@code raisedAt} is within the hold-time of the raise's
 * {@code raisedAt}. This makes P2 HISTORY batch-replay correct — the whole corpus arrives in
 * &lt;1s wall-clock while {@code raisedAt} spans hours, so a clear replayed microseconds after its
 * raise but logically hours later must NOT be treated as a self-clear. In P3 LIVE mode
 * {@code raisedAt} ≈ arrival so the behaviour is identical. The <b>release sweep</b> for an
 * un-cleared held raise stays on the wall-clock {@link Clock} (it is a real scheduled flush so held
 * state does not accumulate unbounded); {@code eventHeldAt} carries the raise's logical time used
 * for clear-matching, {@code wallHeldAt} the arrival time used only by the sweep.
 */
@Component
public class SelfClearStep {

    /**
     * A held raise awaiting either a matching clear or hold-time expiry.
     *
     * @param eventHeldAt the raise's logical {@code raisedAt} (fallback: wall-clock) — used for
     *     event-time clear-matching
     * @param wallHeldAt the raise's wall-clock arrival — used only by the release sweep so held
     *     state is flushed even when no clear ever arrives
     */
    private record Held(AlarmEvent raise, Ruleset ruleset, Path path, Instant eventHeldAt,
            Instant wallHeldAt, String occurredAt, String traceId) {}

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
        Instant wallNow = clock.instant();
        Instant eventNow = EventTime.of(alarm.getRaisedAt(), clock);

        if (alarm.getState() == AlarmEvent.State.RAISED) {
            // Hold the raise; do not emit yet.
            held.put(key, new Held(alarm, ruleset, path, eventNow, wallNow, occurredAt, traceId));
            return StepResult.drop("self_clear_hold");
        }

        // A clear: is there a held raise whose raise is within the hold-time of this clear (event
        // time)? Prefer the clear's clearedAt, else its raisedAt, for the logical clear instant.
        Held h = held.get(key);
        Instant clearEventTime = EventTime.of(
                alarm.getClearedAt() != null ? alarm.getClearedAt() : alarm.getRaisedAt(), clock);
        if (h != null && !clearEventTime.isAfter(h.eventHeldAt().plus(hold))) {
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
            // Sweep on wall-clock: a held raise that no clear ever cleared is flushed once its
            // hold-time has elapsed in real time, so held state never accumulates unbounded.
            if (now.isAfter(h.wallHeldAt().plus(h.ruleset().filterParams().selfClearHoldTime()))) {
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
