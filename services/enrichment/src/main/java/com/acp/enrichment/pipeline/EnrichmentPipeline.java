package com.acp.enrichment.pipeline;

import com.acp.enrichment.kafka.DlqRouter;
import com.acp.enrichment.kafka.EnrichedAlarmProducer;
import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.ruleset.RulesetSelector;
import com.acp.enrichment.trail.TrailLookupException;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The fixed-order enrichment pipeline shared by both listeners (one bean, one instance — the
 * structural basis for criterion 9). Order is exactly: select ruleset &rarr; Normalize &rarr;
 * Dedup &rarr; SelfClear &rarr; FlapDamp &rarr; Chatter &rarr; TrailTag &rarr; Emit (spec
 * "Processing stages are fixed"). Configuration adjusts the mapping/parameters consumed inside
 * the stages; it never adds, removes, reorders, or plugs in stages.
 *
 * <p>A {@link NormalizeException} or an exhausted {@link TrailLookupException} routes the message to
 * the input topic's DLQ. A held self-clear raise that later expires un-cleared is re-injected by the
 * {@link SelfClearStep} sweep at the FlapDamp stage (it has already passed dedup + self-clear).
 */
@Component
public class EnrichmentPipeline {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentPipeline.class);

    private final RulesetSelector selector;
    private final NormalizeStep normalizeStep;
    private final DedupStep dedupStep;
    private final SelfClearStep selfClearStep;
    private final FlapDampStep flapDampStep;
    private final ChatterStep chatterStep;
    private final TrailTagStep trailTagStep;
    private final EnrichedAlarmProducer producer;
    private final DlqRouter dlqRouter;
    private final MeterRegistry meters;

    public EnrichmentPipeline(RulesetSelector selector, NormalizeStep normalizeStep,
            DedupStep dedupStep, SelfClearStep selfClearStep, FlapDampStep flapDampStep,
            ChatterStep chatterStep, TrailTagStep trailTagStep, EnrichedAlarmProducer producer,
            DlqRouter dlqRouter, MeterRegistry meters) {
        this.selector = selector;
        this.normalizeStep = normalizeStep;
        this.dedupStep = dedupStep;
        this.selfClearStep = selfClearStep;
        this.flapDampStep = flapDampStep;
        this.chatterStep = chatterStep;
        this.trailTagStep = trailTagStep;
        this.producer = producer;
        this.dlqRouter = dlqRouter;
        this.meters = meters;
    }

    /**
     * Process one raw alarm through the full pipeline.
     *
     * @param rawPayload the raw alarm payload (source field conventions)
     * @param source the envelope {@code source} (ruleset selector)
     * @param occurredAt the envelope {@code occurredAt} (propagated to the output)
     * @param traceId the envelope {@code traceId} (propagated)
     * @param path the originating path (selects output/DLQ topic)
     * @param rawValue the original wire bytes/string (for DLQ on a normalize/trail failure)
     */
    public void process(Map<String, Object> rawPayload, String source, String occurredAt,
            String traceId, Path path, String rawValue) {
        meters.counter("alarms_consumed_total", "path", path.name(),
                "source", String.valueOf(source)).increment();

        Ruleset ruleset = selector.select(source);

        AlarmEvent canonical;
        try {
            canonical = normalizeStep.normalize(rawPayload, ruleset);
        } catch (NormalizeException e) {
            dlqRouter.route(path, rawValue, e.reason(), e.getMessage());
            return;
        }

        // Dedup.
        StepResult dedup = dedupStep.apply(canonical, ruleset, path);
        if (dedup instanceof StepResult.Drop) {
            return;
        }
        AlarmEvent afterDedup = ((StepResult.Continue) dedup).alarm();

        // Self-clear: a raised alarm is held (drop-for-now), released later by the sweep.
        StepResult sc = selfClearStep.apply(afterDedup, ruleset, path, occurredAt, traceId);
        if (sc instanceof StepResult.Drop) {
            return;
        }
        AlarmEvent afterSelfClear = ((StepResult.Continue) sc).alarm();

        runFromFlapDamp(afterSelfClear, ruleset, path, occurredAt, traceId, rawValue);
    }

    /**
     * The pipeline tail from FlapDamp onward — also the re-entry point for a self-clear raise
     * released after its hold-time elapsed un-cleared (it has already passed dedup + self-clear).
     */
    public void runFromFlapDamp(AlarmEvent alarm, Ruleset ruleset, Path path, String occurredAt,
            String traceId, String rawValue) {
        StepResult flap = flapDampStep.apply(alarm, ruleset, path);
        if (flap instanceof StepResult.Drop) {
            return;
        }
        AlarmEvent afterFlap = ((StepResult.Continue) flap).alarm();

        StepResult chatter = chatterStep.apply(afterFlap, ruleset, path);
        if (chatter instanceof StepResult.Drop) {
            return;
        }
        AlarmEvent survivor = ((StepResult.Continue) chatter).alarm();

        try {
            survivor = trailTagStep.tag(survivor);
        } catch (TrailLookupException e) {
            dlqRouter.route(path, rawValue, "trail_lookup", e.getMessage());
            return;
        }

        try {
            producer.emit(survivor, path, occurredAt, traceId, ruleset.source());
        } catch (RuntimeException e) {
            // Codec re-validation on serialize caught an off-contract output: DLQ, never emit.
            meters.counter("normalize_failures_total", "source", ruleset.source()).increment();
            dlqRouter.route(path, rawValue, "normalize_invalid", e.getMessage());
            log.error("emit failed (off-contract output) for source {}: {}", ruleset.source(),
                    e.getMessage());
        }
    }

    /** Register the self-clear release sink: an expired held raise re-enters at FlapDamp. */
    public void sweepSelfClearReleases() {
        selfClearStep.releaseExpired((raise, ruleset, path, occurredAt, traceId) ->
                runFromFlapDamp(raise, ruleset, path, occurredAt, traceId, null));
    }
}
