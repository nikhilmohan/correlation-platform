package com.acp.enrichment.pipeline;

import com.acp.enrichment.kafka.DlqRouter;
import com.acp.enrichment.kafka.EnrichedAlarmProducer;
import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.ruleset.RulesetRegistry;
import com.acp.enrichment.ruleset.RulesetSelector;
import com.acp.enrichment.support.MutableClock;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a real {@link EnrichmentPipeline} with real steps and capturing test doubles for the
 * producer / DLQ / trail-tag, so pipeline-level outcomes (which topic an alarm emits on, what is
 * DLQ-ed, per-source independence) can be asserted without Kafka. Trail-tagging is faked to set a
 * deterministic trailId so survivors carry a non-empty trailIds list.
 */
public final class PipelineHarness {

    /** One captured emit. */
    public record Emitted(AlarmEvent alarm, Path path, String occurredAt, String traceId,
            String source) {}

    /** One captured DLQ route. */
    public record Dlq(Path path, String rawValue, String reason, String detail) {}

    public final List<Emitted> emitted = new ArrayList<>();
    public final List<Dlq> dlq = new ArrayList<>();
    public final MutableClock clock;
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final RulesetRegistry registry = new RulesetRegistry();
    public final EnrichmentPipeline pipeline;

    public PipelineHarness(List<Ruleset> rulesets) {
        this.clock = MutableClock.atEpoch();
        registry.swap(RulesetRegistry.snapshotOf(rulesets));
        RulesetSelector selector = new RulesetSelector(registry, meters);
        NormalizeStep normalize = new NormalizeStep(meters);
        DedupStep dedup = new DedupStep(meters, clock);
        SelfClearStep selfClearStep = new SelfClearStep(meters, clock);
        FlapDampStep flap = new FlapDampStep(meters, clock);
        ChatterStep chatter = new ChatterStep(meters);

        // A TrailTagStep over a fake client that returns a deterministic trailId per object.
        TrailTagStep trailTag = new TrailTagStep(
                new com.acp.enrichment.trail.TrailBuilderClient(null) {
                    @Override
                    public List<String> getTrailsForObject(String managedObjectId, String domain) {
                        return List.of("trail-" + managedObjectId);
                    }
                }, "core-ip", meters);

        EnrichedAlarmProducer producer = new CapturingProducer();
        DlqRouter dlqRouter = new CapturingDlq();
        this.pipeline = new EnrichmentPipeline(selector, normalize, dedup, selfClearStep, flap,
                chatter, trailTag, producer, dlqRouter, meters);
    }

    public void process(Map<String, Object> raw, String source, Path path) {
        pipeline.process(raw, source, "2026-06-11T10:00:00Z", "trace-1", path, "{rawvalue}");
    }

    public void sweepSelfClear() {
        pipeline.sweepSelfClearReleases();
    }

    /** A capturing producer that records emits instead of sending to Kafka. */
    private final class CapturingProducer extends EnrichedAlarmProducer {
        CapturingProducer() {
            super(null, null, null, null);
        }

        @Override
        public void emit(AlarmEvent alarm, Path path, String occurredAt, String traceId,
                String source) {
            emitted.add(new Emitted(alarm, path, occurredAt, traceId, source));
        }
    }

    /** A capturing DLQ router that records routes instead of sending to Kafka. */
    private final class CapturingDlq extends DlqRouter {
        CapturingDlq() {
            super(null, null, null);
        }

        @Override
        public void route(Path path, String rawValue, String reason, String detail) {
            dlq.add(new Dlq(path, rawValue, reason, detail));
        }
    }
}
