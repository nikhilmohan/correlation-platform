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

/** Acceptance criterion 3 — flap-damping collapses an oscillation burst into one summary. */
class FlapDampStepTest {

    private MutableClock clock;
    private FlapDampStep step;
    private final Ruleset ruleset = TestRulesets.nmsAlpha(); // flapN=3, window 45s

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        step = new FlapDampStep(new SimpleMeterRegistry(), clock);
    }

    private AlarmEvent osc(int i, AlarmEvent.State state) {
        return new AlarmEvent().withAlarmId("a-" + i).withManagedObjectId("Interface:edge1-1")
                .withEventType("communicationsAlarm").withProbableCause("linkDown")
                .withAlarmType("LinkDown").withPerceivedSeverity("CRITICAL")
                .withRaisedAt("2026-06-11T10:00:00Z").withState(state)
                .withTrailIds(new ArrayList<>());
    }

    @Test
    void collapsesOscillationToSingleSummary() {
        // flapN=3: oscillations 1..3 pass; the 4th (exceeds N) yields ONE summary; later osc dropped.
        int summaries = 0;
        int passed = 0;
        int dropped = 0;
        AlarmEvent summary = null;
        for (int i = 1; i <= 8; i++) {
            AlarmEvent.State state = (i % 2 == 1) ? AlarmEvent.State.RAISED
                    : AlarmEvent.State.CLEARED;
            StepResult r = step.apply(osc(i, state), ruleset, Path.HISTORY);
            clock.advance(Duration.ofSeconds(1));
            if (r instanceof StepResult.Continue c) {
                if (c.alarm().getVendorRaw() != null
                        && c.alarm().getVendorRaw().getAdditionalProperties()
                                .containsKey("flapCount")) {
                    summaries++;
                    summary = c.alarm();
                } else {
                    passed++;
                }
            } else {
                dropped++;
            }
        }

        assertThat(summaries).isEqualTo(1);
        assertThat(passed).isEqualTo(3); // first N oscillations pass through normally
        assertThat(dropped).isGreaterThan(0); // the rest of the burst is suppressed
        assertThat(summary).isNotNull();
        assertThat(summary.getState()).isEqualTo(AlarmEvent.State.RAISED);
        assertThat(summary.getAlarmId()).isEqualTo("a-1"); // first alarm identity
        assertThat(summary.getVendorRaw().getAdditionalProperties().get("flapCount"))
                .isEqualTo(4);
    }

    @Test
    void oscillationOfNorFewerIsNotDamped() {
        StepResult last = null;
        for (int i = 1; i <= 3; i++) {
            last = step.apply(osc(i, AlarmEvent.State.RAISED), ruleset, Path.HISTORY);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(last).isInstanceOf(StepResult.Continue.class);
        AlarmEvent a = ((StepResult.Continue) last).alarm();
        assertThat(a.getVendorRaw() == null
                || !a.getVendorRaw().getAdditionalProperties().containsKey("flapCount")).isTrue();
    }
}
