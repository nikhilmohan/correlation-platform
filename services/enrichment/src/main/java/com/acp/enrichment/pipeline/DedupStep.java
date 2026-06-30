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
 * <p><b>State-aware key (B1 fix).</b> The dedup key is
 * {@code (path, source, managedObjectId, eventType, state)} — it includes the alarm {@code state}.
 * A {@code raised} and a {@code cleared} alarm on the same {@code (managedObjectId, eventType)} are
 * <b>not identical alarms</b>, so they must NOT collapse together. Were {@code state} omitted, a
 * {@code cleared} would be swallowed here as a "duplicate" of an earlier {@code raised} and never
 * reach {@link SelfClearStep}/{@link FlapDampStep}, defeating self-clear suppression (criterion 4)
 * and flap-damping (criterion 3). Spec criteria 1 and 2 are unaffected: two same-state duplicates
 * still collapse (criterion 1) and two different {@code eventType}s still pass separately
 * (criterion 2). The downstream self-clear/flap stores deliberately key WITHOUT state
 * ({@link WindowKey}) so they can correlate a raise with its later clear.
 */
@Component
public class DedupStep {

    private record DedupKey(Path path, String source, String managedObjectId, String eventType,
            AlarmEvent.State state) {}

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
                alarm.getEventType(), alarm.getState());
        Duration window = ruleset.filterParams().dedupWindow();
        Instant now = clock.instant();

        Window existing = windows.get(key);
        if (existing != null && !expired(existing.firstSeen(), window, now)) {
            windows.put(key, new Window(existing.firstSeen(), existing.collapsedCount() + 1));
            meters.counter("filtered_total", "filter", "dedup", "source", ruleset.source())
                    .increment();
            return StepResult.drop("dedup");
        }
        windows.put(key, new Window(now, 0));
        return StepResult.cont(alarm);
    }

    private static boolean expired(Instant firstSeen, Duration window, Instant now) {
        return now.isAfter(firstSeen.plus(window));
    }
}
