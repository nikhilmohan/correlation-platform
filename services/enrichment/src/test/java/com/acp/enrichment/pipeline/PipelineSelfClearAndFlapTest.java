package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.support.TestRulesets;
import java.time.Duration;
import java.time.Instant;
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

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");

    private Map<String, Object> nmsAlpha(String alarmId, String state, Instant raisedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("rawSeverity", "CRIT");
        m.put("rawEventType", "LINK_DOWN");
        m.put("ne", "edge1");
        m.put("ifIndex", "1");
        m.put("state", state);
        m.put("raisedAt", raisedAt.toString());
        return m;
    }

    private Map<String, Object> flapSource(String alarmId, String state, Instant raisedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("rawSeverity", "CRIT");
        m.put("rawEventType", "LINK_DOWN");
        m.put("ne", "edge9");
        m.put("ifIndex", "1");
        m.put("state", state);
        m.put("raisedAt", raisedAt.toString());
        return m;
    }

    @Test
    void criterion4_selfClearPairEmitsNothing() {
        // Criterion 4: raise@t=0 then clear@t=3s within the 5s nms-alpha hold -> 0 emitted.
        // (This is the reviewer's exact proof case: it emitted 1 raised alarm before the fix.)
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("r-1", "raised", T0), "nms-alpha", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(3)); // within the 5s hold
        // Clear's raisedAt is 3s after the raise's raisedAt — within the 5s event-time hold.
        h.process(nmsAlpha("c-1", "cleared", T0.plusSeconds(3)), "nms-alpha", Path.HISTORY);

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

        h.process(nmsAlpha("r-only", "raised", T0), "nms-alpha", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(6)); // past the 5s hold (wall-clock), no clear arrived
        h.sweepSelfClear();

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).alarm().getManagedObjectId()).isEqualTo("Interface:edge1-1");
    }

    @Test
    void defect4_multipleDistinctRaisesSameKeyAllReleased_noneDropped() {
        // Defect #4: N raised alarms on the SAME (managedObjectId, eventType), each raisedAt more
        // than the hold-time apart, NO clears -> ALL N must be released/emitted downstream. Under
        // the old single-slot hold map, put() overwrote every earlier held raise so only the last
        // survived (silent signal loss). With the list-backed hold + event-time release, each raise
        // is released on its own expiry as later raises advance the event-time watermark.
        //
        // nms-alpha hold = 5s. Space raisedAt 60s apart (>> 5s) so each held raise's event-time
        // hold has elapsed by the time the next raise arrives -> released as the batch replays.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.defaultRuleset()));

        int n = 8;
        for (int i = 0; i < n; i++) {
            Instant raiseAt = T0.plusSeconds(i * 60L);
            // Same ne/ifIndex -> same managedObjectId Interface:edge1-1, same eventType -> same key.
            h.process(nmsAlpha("r-" + i, "raised", raiseAt), "nms-alpha", Path.HISTORY);
        }
        // End-of-batch flush releases the final still-held raise (its hold-time never elapsed via a
        // later alarm because it is the last one). Mirrors shutdown drain.
        h.pipeline.drainSelfClearHolds();

        assertThat(h.emitted)
                .as("every distinct raise on the same key must be released — none silently dropped")
                .hasSize(n);
        // All are the same object, all RAISED, all survivors of the full pipeline.
        assertThat(h.emitted).allSatisfy(e -> {
            assertThat(e.alarm().getManagedObjectId()).isEqualTo("Interface:edge1-1");
            assertThat(e.alarm().getState()).isEqualTo(
                    com.acp.eventmodel.generated.AlarmEvent.State.RAISED);
        });
        // The distinct alarmIds all made it through (no collapse to one survivor).
        assertThat(h.emitted.stream().map(e -> e.alarm().getAlarmId()).distinct().count())
                .isEqualTo((long) n);
    }

    @Test
    void defect4_twoDistinctRaisesSameKeyBothReleased_noOverwriteDrop() {
        // Two distinct raises on the same (managedObjectId, eventType) that both pass dedup (spaced
        // 25s apart > the 20s nms-alpha dedup window) must BOTH survive self-clear: the second must
        // not overwrite/drop the first still-held raise. No clear arrives -> both emitted.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("r-a", "raised", T0), "nms-alpha", Path.HISTORY);
        h.process(nmsAlpha("r-b", "raised", T0.plusSeconds(25)), "nms-alpha", Path.HISTORY);
        h.pipeline.drainSelfClearHolds();

        assertThat(h.emitted).as("both distinct raises must survive — no overwrite drop").hasSize(2);
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

        // Each oscillation gets a DISTINCT raisedAt 10s apart (> the 1s event-time dedup window so
        // distinct raises are not deduped, well within the 300s flap window so they count as one
        // burst). This mirrors a real flap: the same object oscillates over tens of seconds.
        for (int i = 1; i <= 6; i++) {
            Instant raiseAt = T0.plusSeconds(i * 10L);
            h.process(flapSource("raise-" + i, "raised", raiseAt), "flap-source", Path.HISTORY);
            h.clock.advance(Duration.ofSeconds(2)); // past the 1s hold (wall-clock sweep)
            h.sweepSelfClear();                     // release the held raise into FlapDamp
            // Clear's raisedAt is 3s after its raise — past the 1s event-time hold, so it flows.
            h.process(flapSource("clear-" + i, "cleared", raiseAt.plusSeconds(3)), "flap-source",
                    Path.HISTORY);
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
