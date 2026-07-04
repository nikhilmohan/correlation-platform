package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Acceptance criterion 5 — known-chatter removal drops listed (managedObjectId, eventType). */
class ChatterStepTest {

    private final ChatterStep step = new ChatterStep(new SimpleMeterRegistry());

    private AlarmEvent alarm(String moId, String eventType) {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId(moId)
                .withEventType(eventType).withProbableCause("c").withAlarmType("LinkDown")
                .withPerceivedSeverity("CRITICAL").withRaisedAt("2026-06-11T10:00:00Z")
                .withState(AlarmEvent.State.RAISED).withTrailIds(new ArrayList<>());
    }

    @Test
    void dropsAlarmOnPerSourceChatterList() {
        // nms-alpha chatterList contains (Interface:edge1-12, communicationsAlarm).
        Ruleset ruleset = TestRulesets.nmsAlpha();
        StepResult r = step.apply(alarm("Interface:edge1-12", "communicationsAlarm"), ruleset,
                Path.HISTORY);
        assertThat(r).isInstanceOf(StepResult.Drop.class);
    }

    @Test
    void passesAlarmNotOnChatterList() {
        Ruleset ruleset = TestRulesets.nmsAlpha();
        StepResult r = step.apply(alarm("Interface:edge1-99", "communicationsAlarm"), ruleset,
                Path.HISTORY);
        assertThat(r).isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void matchRequiresBothManagedObjectIdAndEventType() {
        Ruleset ruleset = TestRulesets.nmsAlpha();
        // Same object, different eventType — must NOT match (both key fields required).
        StepResult r = step.apply(alarm("Interface:edge1-12", "equipmentAlarm"), ruleset,
                Path.HISTORY);
        assertThat(r).isInstanceOf(StepResult.Continue.class);
    }
}
