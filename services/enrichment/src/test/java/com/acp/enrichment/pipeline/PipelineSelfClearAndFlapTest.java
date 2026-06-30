package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.support.TestRulesets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Full-pipeline acceptance tests for criteria 3 (flap-damping) and 4 (self-clear suppression),
 * driving the REAL {@link EnrichmentPipeline} end-to-end through {@link PipelineHarness} (not the
 * isolated steps). These are the tests that catch the B1 regression: before the state-aware dedup
 * fix the {@code cleared} alarm was swallowed by dedup as a "duplicate" of its earlier {@code raised}
 * and never reached the self-clear / flap stages — so a transient leaked through (criterion 4 fail)
 * and a flap burst collapsed to one alarm at dedup before flap-damp could summarise it (criterion 3
 * mis-counted). With the fix, raise+clear -> 0 emitted and a flap burst -> exactly one summary.
 */
class PipelineSelfClearAndFlapTest {

    private Map<String, Object> nmsAlpha(String alarmId, String state) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("rawSeverity", "CRIT");
        m.put("rawEventType", "LINK_DOWN");
        m.put("ne", "edge1");
        m.put("ifIndex", "1");
        m.put("state", state);
        m.put("raisedAt", "2026-06-11T10:00:00Z");
        return m;
    }

    private Map<String, Object> flapSource(String alarmId, String state) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("rawSeverity", "CRIT");
        m.put("rawEventType", "LINK_DOWN");
        m.put("ne", "edge9");
        m.put("ifIndex", "1");
        m.put("state", state);
        m.put("raisedAt", "2026-06-11T10:00:00Z");
        return m;
    }

    @Test
    void criterion4_selfClearPairEmitsNothing() {
        // Criterion 4: raise@t=0 then clear@t=3s within the 5s nms-alpha hold -> 0 emitted.
        // (This is the reviewer's exact proof case: it emitted 1 raised alarm before the fix.)
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("r-1", "raised"), "nms-alpha", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(3)); // within the 5s hold
        h.process(nmsAlpha("c-1", "cleared"), "nms-alpha", Path.HISTORY);

        // Sweep after the hold would have elapsed: nothing must be released, because the clear
        // suppressed the held raise.
        h.clock.advance(Duration.ofSeconds(6));
        h.sweepSelfClear();

        assertThat(h.emitted)
                .as("self-clear must suppress the raise+clear transient pair entirely")
                .isEmpty();
    }

    @Test
    void criterion4_unclearedRaiseIsReleasedAndEmitted() {
        // Sanity counterpart: a raise that is never cleared IS emitted after the hold elapses, so
        // the suppression above is genuine, not a blanket drop.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("r-only", "raised"), "nms-alpha", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(6)); // past the 5s hold, no clear arrived
        h.sweepSelfClear();

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).alarm().getManagedObjectId()).isEqualTo("Interface:edge1-1");
    }

    @Test
    void criterion3_flapBurstEmitsExactlyOneSummary() {
        // Criterion 3: a burst of raise/clear oscillations exceeding flapN within the flap window
        // collapses to exactly ONE summary AlarmEvent — driven through the REAL pipeline.
        //
        // flap-source: hold=1s, flapN=3, flapWindow=300s, dedupWindow=1s. Each oscillation step
        // advances the clock by 2s (past the 1s hold) so the raise is released into FlapDamp by the
        // sweep and the following clear flows through (state-aware dedup lets the clear pass — the
        // whole point of B1). Over the burst, the raise/clear alarms reaching FlapDamp exceed N and
        // produce one summary; the remainder of the burst is damped.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.flapSource(),
                TestRulesets.defaultRuleset()));

        for (int i = 1; i <= 6; i++) {
            h.process(flapSource("raise-" + i, "raised"), "flap-source", Path.HISTORY);
            h.clock.advance(Duration.ofSeconds(2)); // past the 1s hold
            h.sweepSelfClear();                     // release the held raise into FlapDamp
            h.process(flapSource("clear-" + i, "cleared"), "flap-source", Path.HISTORY);
            h.clock.advance(Duration.ofSeconds(2));
            h.sweepSelfClear();
        }

        long summaries = h.emitted.stream()
                .filter(e -> e.alarm().getVendorRaw() != null
                        && e.alarm().getVendorRaw().getAdditionalProperties()
                                .containsKey("flapCount"))
                .count();
        assertThat(summaries).as("flap burst must yield exactly one summary AlarmEvent")
                .isEqualTo(1);

        // And the summary carries the flap metadata on existing fields (no contract change).
        var summary = h.emitted.stream()
                .filter(e -> e.alarm().getVendorRaw() != null
                        && e.alarm().getVendorRaw().getAdditionalProperties()
                                .containsKey("flapCount"))
                .findFirst().orElseThrow();
        assertThat(summary.alarm().getVendorRaw().getAdditionalProperties())
                .containsKey("flapWindowSeconds");
    }
}
