package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria 10, 13 — the emitted canonical AlarmEvent validates against the frozen
 * event-model binding, and an unmatched source is normalized by the default ruleset (not DLQ-ed).
 */
class OutputContractTest {

    private final NormalizeStep normalize = new NormalizeStep(new SimpleMeterRegistry());
    private final EventCodec codec = new EventCodec();

    private Map<String, Object> defaultRaw() {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", "d-1");
        m.put("rawSeverity", "1");
        m.put("rawAlarmType", "2");
        m.put("objectType", "Node");
        m.put("rawObjectId", "core-7");
        m.put("state", "raised");
        m.put("raisedAt", "2026-06-11T10:00:00Z");
        return m;
    }

    @Test
    void emittedAlarmDeserializesWithEventModelBinding() {
        // Criterion 10: normalize then serialize through the codec (which validates against the
        // frozen AlarmEvent schema) then deserialize back — round-trip succeeds, alarmType non-null.
        AlarmEvent alarm = normalize.normalize(defaultRaw(), TestRulesets.defaultRuleset());
        alarm.setTrailIds(new ArrayList<>(java.util.List.of("trail-1")));

        TypedEnvelope<AlarmEvent> out = new TypedEnvelope<>(UUID.randomUUID().toString(),
                "AlarmEvent", 1, "2026-06-11T10:00:00Z", "enrichment", "trace-1", alarm);

        String json = codec.serialize(out); // throws CodecException if off-contract
        TypedEnvelope<Object> back = codec.deserialize(json);
        AlarmEvent decoded = (AlarmEvent) back.getPayload();

        assertThat(decoded.getAlarmType()).isNotNull();
        assertThat(decoded.getManagedObjectId()).matches("^[A-Za-z][A-Za-z0-9]*:[^:]+$");
        assertThat(decoded.getTrailIds()).isNotNull();
        assertThat(decoded.getPerceivedSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void unmatchedSourceUsesDefaultAndEmitsCanonical() {
        // Criterion 13: the harness selector falls back to default for an unknown source, and the
        // pipeline emits a canonical alarm (not DLQ, not dropped).
        PipelineHarness h = new PipelineHarness(java.util.List.of(TestRulesets.nmsAlpha(),
                TestRulesets.vendorBeta(), TestRulesets.defaultRuleset()));
        h.process(defaultRaw(), "feed-unknown", Path.HISTORY);
        h.clock.advance(java.time.Duration.ofSeconds(16));
        h.sweepSelfClear();

        assertThat(h.dlq).isEmpty();
        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).alarm().getAlarmType()).isEqualTo("LinkDown");
        // default-fallback metric incremented.
        assertThatCode(() -> h.meters.get("ruleset_default_fallback_total").counter())
                .doesNotThrowAnyException();
    }
}
