package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.support.MutableClock;
import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance criteria 1, 2 — dedup count-collapse on the composite key. */
class DedupStepTest {

    private MutableClock clock;
    private DedupStep step;
    private final Ruleset ruleset = TestRulesets.nmsAlpha();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        step = new DedupStep(new SimpleMeterRegistry(), clock);
    }

    private AlarmEvent alarm(String moId, String eventType) {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId(moId)
                .withEventType(eventType).withProbableCause("c").withAlarmType("LinkDown")
                .withPerceivedSeverity("CRITICAL").withRaisedAt("2026-06-11T10:00:00Z")
                .withState(AlarmEvent.State.RAISED).withTrailIds(new ArrayList<>());
    }

    @Test
    void collapsesDuplicateCompositeKeyWithinWindow() {
        StepResult first = step.apply(alarm("Interface:edge1-1", "communicationsAlarm"), ruleset,
                Path.HISTORY);
        clock.advance(Duration.ofSeconds(5)); // still within the 20s dedup window
        StepResult second = step.apply(alarm("Interface:edge1-1", "communicationsAlarm"), ruleset,
                Path.HISTORY);

        assertThat(first).isInstanceOf(StepResult.Continue.class);
        assertThat(second).isInstanceOf(StepResult.Drop.class);
    }

    @Test
    void keepsDistinctEventTypesForSameObject() {
        StepResult a = step.apply(alarm("Interface:edge1-1", "communicationsAlarm"), ruleset,
                Path.HISTORY);
        StepResult b = step.apply(alarm("Interface:edge1-1", "equipmentAlarm"), ruleset,
                Path.HISTORY);

        assertThat(a).isInstanceOf(StepResult.Continue.class);
        assertThat(b).isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void freshWindowAfterExpiryPassesAgain() {
        step.apply(alarm("Interface:edge1-1", "communicationsAlarm"), ruleset, Path.HISTORY);
        clock.advance(Duration.ofSeconds(21)); // past the 20s window
        StepResult after = step.apply(alarm("Interface:edge1-1", "communicationsAlarm"), ruleset,
                Path.HISTORY);
        assertThat(after).isInstanceOf(StepResult.Continue.class);
    }
}
