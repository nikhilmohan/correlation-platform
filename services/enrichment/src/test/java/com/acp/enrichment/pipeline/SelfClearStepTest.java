package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.support.MutableClock;
import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance criterion 4 — self-clear suppression of transients within the per-source hold-time. */
class SelfClearStepTest {

    private MutableClock clock;
    private SelfClearStep step;
    private final Ruleset ruleset = TestRulesets.nmsAlpha(); // 5s hold

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        step = new SelfClearStep(new SimpleMeterRegistry(), clock);
    }

    private AlarmEvent alarm(AlarmEvent.State state) {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId("Interface:edge1-1")
                .withEventType("communicationsAlarm").withProbableCause("linkDown")
                .withAlarmType("LinkDown").withPerceivedSeverity("CRITICAL")
                .withRaisedAt("2026-06-11T10:00:00Z").withState(state)
                .withTrailIds(new ArrayList<>());
    }

    @Test
    void suppressesTransientClearedWithinHoldTime() {
        // Raise is held (drop-for-now), clear within 5s suppresses both — emit nothing.
        StepResult raise = step.apply(alarm(AlarmEvent.State.RAISED), ruleset, Path.HISTORY, "2026-06-11T10:00:00Z", "trace-1");
        clock.advance(Duration.ofSeconds(3));
        StepResult clear = step.apply(alarm(AlarmEvent.State.CLEARED), ruleset, Path.HISTORY, "2026-06-11T10:00:00Z", "trace-1");

        assertThat(raise).isInstanceOf(StepResult.Drop.class);
        assertThat(clear).isInstanceOf(StepResult.Drop.class);

        // Nothing should be released by a sweep — the transient cleared in time.
        List<AlarmEvent> released = new ArrayList<>();
        step.releaseExpired((a, r, p, o, t) -> released.add(a));
        assertThat(released).isEmpty();
    }

    @Test
    void releasesHeldRaiseWhenHoldElapsesUncleared() {
        step.apply(alarm(AlarmEvent.State.RAISED), ruleset, Path.HISTORY, "2026-06-11T10:00:00Z", "trace-1");
        clock.advance(Duration.ofSeconds(6)); // past the 5s hold, no clear arrived

        List<AlarmEvent> released = new ArrayList<>();
        step.releaseExpired((a, r, p, o, t) -> released.add(a));
        assertThat(released).hasSize(1);
        assertThat(released.get(0).getState()).isEqualTo(AlarmEvent.State.RAISED);
    }

    @Test
    void clearAfterHoldElapsedIsNotSuppressed() {
        step.apply(alarm(AlarmEvent.State.RAISED), ruleset, Path.HISTORY, "2026-06-11T10:00:00Z", "trace-1");
        clock.advance(Duration.ofSeconds(6));
        step.releaseExpired((a, r, p, o, t) -> { }); // raise released
        StepResult clear = step.apply(alarm(AlarmEvent.State.CLEARED), ruleset, Path.HISTORY, "2026-06-11T10:00:00Z", "trace-1");
        assertThat(clear).isInstanceOf(StepResult.Continue.class);
    }
}
