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
 * Count-collapse dedup on the composite key {@code (path, source, managedObjectId, eventType)}
 * within the per-source {@code dedupWindow} (spec criteria 1, 2; design step 3). The first alarm
 * for a key within the window passes and records first-seen; subsequent identical-key alarms within
 * the window are dropped while a collapsed count increments. Window eviction starts a fresh window.
 */
@Component
public class DedupStep {

    private record Window(Instant firstSeen, long collapsedCount) {}

    private final ConcurrentHashMap<WindowKey, Window> windows = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public DedupStep(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    public StepResult apply(AlarmEvent alarm, Ruleset ruleset, Path path) {
        WindowKey key = new WindowKey(path, ruleset.source(), alarm.getManagedObjectId(),
                alarm.getEventType());
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
