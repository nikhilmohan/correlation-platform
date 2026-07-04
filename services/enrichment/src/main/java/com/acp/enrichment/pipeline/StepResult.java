package com.acp.enrichment.pipeline;

import com.acp.eventmodel.generated.AlarmEvent;

/**
 * The outcome of a pipeline step: pass-through (continue with the alarm), drop (emit nothing), or
 * replace (continue with a substituted alarm, e.g. a flap summary). Design "EnrichmentPipeline".
 */
public sealed interface StepResult {

    /** Continue down the pipeline with the (possibly substituted) alarm. */
    record Continue(AlarmEvent alarm) implements StepResult {}

    /** The alarm is dropped by this step; emit nothing. {@code reason} labels the filter metric. */
    record Drop(String reason) implements StepResult {}

    static StepResult cont(AlarmEvent alarm) {
        return new Continue(alarm);
    }

    static StepResult drop(String reason) {
        return new Drop(reason);
    }
}
