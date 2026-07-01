package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
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
 * downstream pipeline. A clear arriving after the hold-time elapsed (the raise already released) is
 * not suppressed and passes through.
 *
 * <p>Because the hold-time is per source, the same transient is suppressed under a short-hold source
 * and emitted under a long-hold source (criterion 11).
 *
 * <p><b>Multi-hold per key (Defect #4 fix).</b> The hold store keeps a <b>list</b> of held raises
 * per {@link WindowKey} — it must never overwrite/drop a still-held raise when a second, distinct
 * raise arrives for the same {@code (path, source, managedObjectId, eventType)}. Two raises whose
 * {@code raisedAt} differ by more than the hold-time are two separate events; each gets its own
 * hold&rarr;release lifecycle. (The previous single-slot {@code put} silently dropped the earlier
 * held raise, destroying signal in the all-{@code raised} P2 history batch.) A clear cancels
 * (suppresses) exactly the one held raise it matches by event time; each remaining held raise is
 * released on its own expiry.
 *
 * <p><b>Event-time clear-matching AND event-time release (raisedAt, not wall-clock).</b> Whether a
 * clear counts as self-clearing a held raise is decided over the alarms' own logical time: the
 * transient is suppressed iff the clear's logical time is within the hold-time of the raise's
 * {@code raisedAt}. Equally, in P2 HISTORY batch-replay the whole corpus arrives in &lt;1s
 * wall-clock while {@code raisedAt} spans hours, so a scheduled wall-clock sweep alone would not
 * fire before the batch ends and every held raise would be stranded (never emitted). The step
 * therefore also releases a held raise as soon as a <b>later alarm's</b> {@code raisedAt} advances
 * the observed event-time high-watermark past that raise's {@code raisedAt + holdTime}
 * ({@link #releaseEventExpired}, driven per-alarm by the pipeline). The wall-clock sweep
 * ({@link #releaseExpired}) remains as the LIVE backstop so held state never accumulates unbounded
 * when {@code raisedAt} &asymp; arrival. A final {@link #drainAll} flush guarantees nothing is
 * stranded at end-of-batch / shutdown.
 *
 * <p><b>Silent-loss guard (Defect #4).</b> Every held raise is counted
 * ({@code self_clear_held_total}) and every released raise is counted
 * ({@code self_clear_released_total}); a suppressed transient increments
 * {@code filtered_total{filter=self_clear}}. Thus {@code held == released + suppressed} once the
 * store is drained — any silent loss (the old overwrite bug) would show as held &gt; released +
 * suppressed. A {@code self_clear_held_current} gauge exposes the outstanding-hold count so a
 * stranded batch is visible immediately.
 */
@Component
public class SelfClearStep {

    /**
     * A held raise awaiting either a matching clear or hold-time expiry.
     *
     * @param eventHeldAt the raise's logical {@code raisedAt} (fallback: wall-clock) — used for
     *     event-time clear-matching and event-time release
     * @param wallHeldAt the raise's wall-clock arrival — used only by the LIVE release sweep so
     *     held state is flushed even when no clear ever arrives
     */
    private record Held(AlarmEvent raise, Ruleset ruleset, Path path, Instant eventHeldAt,
            Instant wallHeldAt, String occurredAt, String traceId) {}

    /**
     * List-backed hold store: one <b>list</b> of held raises per key so a distinct second raise
     * never overwrites a still-held first raise (Defect #4). All access is guarded on the list
     * instance (see {@link #synchronizedListOps}); the outer map is a {@link ConcurrentHashMap}.
     */
    private final ConcurrentHashMap<WindowKey, List<Held>> held = new ConcurrentHashMap<>();

    /**
     * The highest {@code raisedAt}/{@code clearedAt} logical time observed across all alarms — the
     * event-time high-watermark that drives batch-replay release ({@link #releaseEventExpired}).
     */
    private volatile Instant eventHighWatermark = Instant.MIN;

    private final MeterRegistry meters;
    private final Clock clock;

    public SelfClearStep(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
        // Observability: how many held raises are currently outstanding (should trend to 0 as the
        // batch/live stream flushes). A persistently large value signals stranded holds.
        meters.gauge("self_clear_held_current", this, SelfClearStep::heldCount);
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
        advanceWatermark(eventNow);

        if (alarm.getState() == AlarmEvent.State.RAISED) {
            // Append (never overwrite) — each distinct raise gets its own hold->release lifecycle.
            List<Held> list = held.computeIfAbsent(key, k -> synchronizedList());
            synchronized (list) {
                list.add(new Held(alarm, ruleset, path, eventNow, wallNow, occurredAt, traceId));
            }
            meters.counter("self_clear_held_total", "source", ruleset.source()).increment();
            return StepResult.drop("self_clear_hold");
        }

        // A clear: does it match a held raise within the hold-time (event time)? Prefer the clear's
        // clearedAt, else its raisedAt, for the logical clear instant.
        Instant clearEventTime = EventTime.of(
                alarm.getClearedAt() != null ? alarm.getClearedAt() : alarm.getRaisedAt(), clock);
        advanceWatermark(clearEventTime);

        List<Held> list = held.get(key);
        if (list != null) {
            synchronized (list) {
                for (Iterator<Held> it = list.iterator(); it.hasNext();) {
                    Held h = it.next();
                    if (!clearEventTime.isAfter(h.eventHeldAt().plus(hold))) {
                        it.remove();
                        if (list.isEmpty()) {
                            held.remove(key, list);
                        }
                        meters.counter("filtered_total", "filter", "self_clear",
                                "source", ruleset.source()).increment();
                        return StepResult.drop("self_clear");
                    }
                }
            }
        }
        // No held raise matched (already released / past hold-time / never held): let the clear flow.
        return StepResult.cont(alarm);
    }

    /**
     * Release every held raise whose <b>event-time</b> hold has elapsed — i.e. the observed
     * event-time high-watermark has advanced past its {@code raisedAt + holdTime}. This is the
     * batch-replay flush: as later alarms arrive with larger {@code raisedAt}, earlier un-cleared
     * held raises are released in order, so a P2 history batch (all raises, &lt;1s wall-clock,
     * {@code raisedAt} spanning hours) emits every un-cleared raise instead of stranding it. Driven
     * by the pipeline after each processed alarm.
     *
     * @param sink receives {@code (releasedRaise, ruleset, path, occurredAt, traceId)} per release
     */
    public void releaseEventExpired(ReleaseSink sink) {
        Instant watermark = this.eventHighWatermark;
        releaseMatching(sink,
                h -> watermark.isAfter(h.eventHeldAt().plus(h.ruleset().filterParams()
                        .selfClearHoldTime())));
    }

    /**
     * Release every held raise whose <b>wall-clock</b> hold-time has elapsed (LIVE backstop). In
     * LIVE mode {@code raisedAt} &asymp; arrival so this and the event-time release coincide; its
     * job is to guarantee held state never accumulates unbounded when no later alarm advances the
     * watermark. Invoked by the scheduled sweep on the same instant {@link Clock}.
     *
     * @param sink receives {@code (releasedRaise, ruleset, path, occurredAt, traceId)} per release
     */
    public void releaseExpired(ReleaseSink sink) {
        Instant now = clock.instant();
        releaseMatching(sink,
                h -> now.isAfter(h.wallHeldAt().plus(h.ruleset().filterParams()
                        .selfClearHoldTime())));
    }

    /**
     * Release EVERY remaining held raise unconditionally — the end-of-batch / shutdown flush that
     * guarantees no un-cleared held raise is stranded (Defect #4 caution). Safe to call repeatedly.
     *
     * @param sink receives {@code (releasedRaise, ruleset, path, occurredAt, traceId)} per release
     */
    public void drainAll(ReleaseSink sink) {
        releaseMatching(sink, h -> true);
    }

    private void releaseMatching(ReleaseSink sink, java.util.function.Predicate<Held> ready) {
        for (var entry : held.entrySet()) {
            List<Held> list = entry.getValue();
            List<Held> toRelease = new ArrayList<>();
            synchronized (list) {
                for (Iterator<Held> it = list.iterator(); it.hasNext();) {
                    Held h = it.next();
                    if (ready.test(h)) {
                        toRelease.add(h);
                        it.remove();
                    }
                }
                if (list.isEmpty()) {
                    held.remove(entry.getKey(), list);
                }
            }
            for (Held h : toRelease) {
                meters.counter("self_clear_released_total", "source", h.ruleset().source())
                        .increment();
                sink.release(h.raise(), h.ruleset(), h.path(), h.occurredAt(), h.traceId());
            }
        }
    }

    private void advanceWatermark(Instant t) {
        if (t.isAfter(eventHighWatermark)) {
            eventHighWatermark = t;
        }
    }

    private static List<Held> synchronizedList() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /** Total held raises currently outstanding across all keys (for the guard gauge and tests). */
    int heldCount() {
        int n = 0;
        for (List<Held> list : held.values()) {
            synchronized (list) {
                n += list.size();
            }
        }
        return n;
    }

    /** Callback for a released (un-cleared) held raise re-entering the pipeline after FlapDamp. */
    @FunctionalInterface
    public interface ReleaseSink {
        void release(AlarmEvent raise, Ruleset ruleset, Path path, String occurredAt,
                String traceId);
    }
}
