package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.support.TestRulesets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Acceptance criteria 7, 8, 9, 11, 14 — routing by path, same instance, per-source params. */
class PipelineRoutingAndSourceTest {

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

    private Map<String, Object> vendorBeta(String alarmId, String state) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("rawSeverity", "P1");
        m.put("rawAlarmType", "port-fault");
        m.put("chassis", "c9");
        m.put("slot", "3");
        m.put("port", "7");
        m.put("state", state);
        m.put("raisedAt", "2026-06-11T10:00:00Z");
        return m;
    }

    @Test
    void historyAlarmEmittedOnEnrichedTopicAndLiveOnLive() {
        // Criteria 7, 8, 9: one harness (one instance) routes by Path.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.vendorBeta(), TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("h-1", "raised"), "nms-alpha", Path.HISTORY);
        h.sweepSelfClear(); // released after hold (raised alarm)
        h.clock.advance(Duration.ofSeconds(6));
        h.sweepSelfClear();

        h.process(nmsAlpha("l-1", "raised"), "nms-alpha", Path.LIVE);
        h.clock.advance(Duration.ofSeconds(6));
        h.sweepSelfClear();

        assertThat(h.emitted).extracting(PipelineHarness.Emitted::path)
                .contains(Path.HISTORY, Path.LIVE);
        long historyCount = h.emitted.stream().filter(e -> e.path() == Path.HISTORY).count();
        long liveCount = h.emitted.stream().filter(e -> e.path() == Path.LIVE).count();
        assertThat(historyCount).isEqualTo(1);
        assertThat(liveCount).isEqualTo(1);
    }

    @Test
    void sameTransientSuppressedForSourceAEmittedForSourceB() {
        // Criterion 11: a transient (raise then clear at +8s) is suppressed under vendor-beta
        // (120s hold) but emitted under nms-alpha (5s hold — clear is past the released raise).
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.vendorBeta(), TestRulesets.defaultRuleset()));

        // nms-alpha: raise, then no clear within 5s -> released and emitted.
        h.process(nmsAlpha("a-r", "raised"), "nms-alpha", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(6));
        h.sweepSelfClear(); // raise released and emitted
        h.process(nmsAlpha("a-c", "cleared"), "nms-alpha", Path.HISTORY); // clear flows (not held)

        // vendor-beta: raise then clear at +8s, well within 120s hold -> suppressed.
        h.process(vendorBeta("b-r", "raised"), "vendor-beta", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(8));
        h.process(vendorBeta("b-c", "cleared"), "vendor-beta", Path.HISTORY);

        boolean nmsEmitted = h.emitted.stream().anyMatch(e -> "nms-alpha".equals(e.source()));
        boolean vendorEmitted = h.emitted.stream().anyMatch(e -> "vendor-beta".equals(e.source()));
        assertThat(nmsEmitted).as("nms-alpha transient emitted (short hold)").isTrue();
        assertThat(vendorEmitted).as("vendor-beta transient suppressed (long hold)").isFalse();
    }

    @Test
    void allSourcesEmitValidCanonicalAlarmEventsWithAlarmType() {
        // Criteria 14, 16: across two sources, every emitted alarm is canonical with a vocab alarmType.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.nmsAlpha(),
                TestRulesets.vendorBeta(), TestRulesets.defaultRuleset()));

        h.process(nmsAlpha("a-1", "raised"), "nms-alpha", Path.HISTORY);
        h.process(vendorBeta("b-1", "raised"), "vendor-beta", Path.HISTORY);
        h.clock.advance(Duration.ofSeconds(130));
        h.sweepSelfClear();

        assertThat(h.emitted).hasSize(2);
        assertThat(h.emitted).allSatisfy(e -> {
            assertThat(e.alarm().getAlarmType()).isNotNull();
            assertThat(e.alarm().getManagedObjectId()).matches("^[A-Za-z][A-Za-z0-9]*:[^:]+$");
            assertThat(e.alarm().getTrailIds()).isNotNull();
        });
        assertThat(h.emitted).extracting(e -> e.alarm().getAlarmType())
                .containsExactlyInAnyOrder("LinkDown", "PortDown");
    }
}
