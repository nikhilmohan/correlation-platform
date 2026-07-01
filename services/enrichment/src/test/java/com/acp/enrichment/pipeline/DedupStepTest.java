package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.support.MutableClock;
import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance criteria 1, 2 + FIX #3 — dedup count-collapse on the composite key, windowed on the
 * alarm's logical {@code raisedAt} (not wall-clock). */
class DedupStepTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");

    private MutableClock clock;
    private DedupStep step;
    private final Ruleset ruleset = TestRulesets.nmsAlpha(); // 20s dedup window

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        step = new DedupStep(new SimpleMeterRegistry(), clock);
    }

    private AlarmEvent alarm(String moId, String eventType, Instant raisedAt) {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId(moId)
                .withEventType(eventType).withProbableCause("c").withAlarmType("LinkDown")
                .withPerceivedSeverity("CRITICAL").withRaisedAt(raisedAt.toString())
                .withState(AlarmEvent.State.RAISED).withTrailIds(new ArrayList<>());
    }

    @Test
    void collapsesDuplicateCompositeKeyWithinWindow() {
        // Two alarms 5s apart by raisedAt (within the 20s window) collapse.
        StepResult first = step.apply(alarm("Interface:edge1-1", "communicationsAlarm", T0),
                ruleset, Path.HISTORY);
        StepResult second = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0.plusSeconds(5)), ruleset,
                Path.HISTORY);

        assertThat(first).isInstanceOf(StepResult.Continue.class);
        assertThat(second).isInstanceOf(StepResult.Drop.class);
    }

    @Test
    void keepsDistinctEventTypesForSameObject() {
        StepResult a = step.apply(alarm("Interface:edge1-1", "communicationsAlarm", T0), ruleset,
                Path.HISTORY);
        StepResult b = step.apply(alarm("Interface:edge1-1", "equipmentAlarm", T0), ruleset,
                Path.HISTORY);

        assertThat(a).isInstanceOf(StepResult.Continue.class);
        assertThat(b).isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void doesNotCollapseClearAgainstRaise() {
        // B1: a raised and a cleared on the same (managedObjectId, eventType) are NOT identical
        // alarms, so the clear must pass through dedup (state is part of the dedup key). Otherwise
        // the clear is swallowed before self-clear/flap-damp can see it.
        StepResult raise = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0)
                        .withState(AlarmEvent.State.RAISED),
                ruleset, Path.HISTORY);
        StepResult clear = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0.plusSeconds(3))
                        .withState(AlarmEvent.State.CLEARED),
                ruleset, Path.HISTORY);

        assertThat(raise).isInstanceOf(StepResult.Continue.class);
        assertThat(clear).as("clear must not be deduped against the earlier raise")
                .isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void stillCollapsesTwoIdenticalClears() {
        // Two cleared alarms with the same key DO collapse (criterion 1 holds for any single state).
        StepResult first = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0)
                        .withState(AlarmEvent.State.CLEARED),
                ruleset, Path.HISTORY);
        StepResult second = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0.plusSeconds(2))
                        .withState(AlarmEvent.State.CLEARED),
                ruleset, Path.HISTORY);

        assertThat(first).isInstanceOf(StepResult.Continue.class);
        assertThat(second).isInstanceOf(StepResult.Drop.class);
    }

    @Test
    void freshWindowAfterRaisedAtExpiryPassesAgain() {
        // raisedAt 21s apart (past the 20s window) -> a fresh window, second passes.
        step.apply(alarm("Interface:edge1-1", "communicationsAlarm", T0), ruleset, Path.HISTORY);
        StepResult after = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0.plusSeconds(21)), ruleset,
                Path.HISTORY);
        assertThat(after).isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void batchReplayHoursApartByRaisedAtIsNotCollapsedEvenWhenWallClockConstant() {
        // FIX #3: the wall-clock (injected Clock) is NEVER advanced — mimicking a <1s batch replay —
        // yet two alarms 5h apart by raisedAt must NOT collapse. The old wall-clock windowing would
        // have wrongly dropped the second.
        StepResult first = step.apply(alarm("Interface:edge1-1", "communicationsAlarm", T0),
                ruleset, Path.HISTORY);
        StepResult fiveHoursLater = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0.plusSeconds(5 * 3600)),
                ruleset, Path.HISTORY);

        assertThat(first).isInstanceOf(StepResult.Continue.class);
        assertThat(fiveHoursLater).as("5h-apart alarms are distinct occurrences, not duplicates")
                .isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void fallsBackToClockWhenRaisedAtMissing() {
        // Defensive: a null raisedAt uses the injected clock so behaviour is well-defined.
        AlarmEvent noRaisedAt = alarm("Interface:edge1-1", "communicationsAlarm", T0)
                .withRaisedAt(null);
        StepResult first = step.apply(noRaisedAt, ruleset, Path.HISTORY);
        StepResult second = step.apply(
                alarm("Interface:edge1-1", "communicationsAlarm", T0).withRaisedAt(null),
                ruleset, Path.HISTORY);
        assertThat(first).isInstanceOf(StepResult.Continue.class);
        // Same clock instant (not advanced) -> within window -> collapsed.
        assertThat(second).isInstanceOf(StepResult.Drop.class);
    }
}
