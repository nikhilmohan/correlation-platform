package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Count-collapse dedup of <b>repeated identical alarms</b> within the per-source {@code dedupWindow}
 * (spec criteria 1, 2 and the spec wording "repeated identical alarms on
 * {@code (managedObjectId, eventType)}"; design step 3). The first alarm for a key within the window
 * passes and records first-seen; subsequent identical-key alarms within the window are dropped while
 * a collapsed count increments. Window eviction starts a fresh window.
 *
 * <p><b>Event-time windowing (raisedAt, not wall-clock).</b> The dedup window is measured over the
 * alarm's own logical {@code raisedAt} timestamp, NOT the wall-clock arrival time. The spec's dedup
 * is "repeated identical alarms within the per-source dedup window" — the window is over <i>alarm
 * time</i>. In P2 HISTORY mode the Simulator batch-replays the whole corpus in &lt;1s wall-clock
 * while the alarms' {@code raisedAt} span many hours; wall-clock windowing would wrongly collapse
 * alarms hours apart into a single "duplicate", destroying signal retention. Windowing on
 * {@code raisedAt} dedups by logical alarm time in both modes: in P3 LIVE mode {@code raisedAt}
 * ≈ wall-clock arrival, so the behaviour is identical there. The window {@code firstSeen} is the
 * first alarm's {@code raisedAt}; an alarm whose {@code raisedAt} is more than {@code dedupWindow}
 * after {@code firstSeen} starts a fresh window. If {@code raisedAt} is absent/unparseable the
 * injected {@link Clock} is used as a safe fallback (keeps live-mode behaviour if a producer omits
 * it). The {@link Clock} is retained only for that fallback.
 *
 * <p><b>State-aware key (B1 fix).</b> The dedup key is
 * {@code (path, source, managedObjectId, eventType, alarmType, state)} — it includes the alarm
 * {@code state}. A {@code raised} and a {@code cleared} alarm on the same
 * {@code (managedObjectId, eventType, alarmType)} are <b>not identical alarms</b>, so they must NOT
 * collapse together. Were {@code state} omitted, a {@code cleared} would be swallowed here as a
 * "duplicate" of an earlier {@code raised} and never reach {@link SelfClearStep}/{@link FlapDampStep},
 * defeating self-clear suppression (criterion 4) and flap-damping (criterion 3). Spec criteria 1
 * and 2 are unaffected: two same-state duplicates still collapse (criterion 1) and two different
 * {@code eventType}s still pass separately (criterion 2). The downstream self-clear/flap stores
 * deliberately key WITHOUT state ({@link WindowKey}) so they can correlate a raise with its later
 * clear.
 *
 * <p><b>alarmType in the key (Defect #7 fix).</b> The dedup key also includes {@code alarmType}.
 * The spec defines dedup as collapsing <b>repeated IDENTICAL alarms</b>; two alarms with a
 * different {@code alarmType} are NOT identical. In this domain a fault cascade fires many distinct
 * {@code alarmType}s on one object that share ONE coarse X.733 {@code eventType} (e.g. six
 * IGP-adjacency alarms — {@code AdjDown}, {@code ISISAdjacencyDown}, {@code OSPFAdjacencyDown},
 * {@code BGPPeerDown}, {@code RouteFlap}, {@code LDPSessionDown} — all {@code communicationsAlarm}).
 * Keying only on {@code eventType} collapsed those distinct cascade members onto one arbitrary
 * survivor. {@code alarmType} is the canonical, 1:1-finer-than-{@code probableCause} discriminator,
 * so it is the correct token to make dedup collapse only genuinely-identical alarms.
 */
@Component
public class DedupStep {

    private record DedupKey(Path path, String source, String managedObjectId, String eventType,
            String alarmType, AlarmEvent.State state) {}

    private record Window(Instant firstSeen, long collapsedCount) {}

    private final ConcurrentHashMap<DedupKey, Window> windows = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public DedupStep(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    public StepResult apply(AlarmEvent alarm, Ruleset ruleset, Path path) {
        DedupKey key = new DedupKey(path, ruleset.source(), alarm.getManagedObjectId(),
                alarm.getEventType(), alarm.getAlarmType(), alarm.getState());
        Duration window = ruleset.filterParams().dedupWindow();
        Instant now = EventTime.of(alarm.getRaisedAt(), clock);

        Window existing = windows.get(key);
        // Only treat as duplicate when this alarm's raisedAt falls within [firstSeen, firstSeen+window].
        if (existing != null && within(existing.firstSeen(), window, now)) {
            windows.put(key, new Window(existing.firstSeen(), existing.collapsedCount() + 1));
            meters.counter("filtered_total", "filter", "dedup", "source", ruleset.source())
                    .increment();
            return StepResult.drop("dedup");
        }
        windows.put(key, new Window(now, 0));
        return StepResult.cont(alarm);
    }

    /**
     * @return {@code true} iff {@code eventTime} is within the window opened at {@code firstSeen}.
     *     Uses absolute distance so an out-of-order replay (a later-arriving earlier-raisedAt
     *     alarm) inside the window still collapses; anything more than {@code window} apart in
     *     logical alarm time is a distinct occurrence and opens a fresh window.
     */
    private static boolean within(Instant firstSeen, Duration window, Instant eventTime) {
        return Duration.between(firstSeen, eventTime).abs().compareTo(window) <= 0;
    }
}
