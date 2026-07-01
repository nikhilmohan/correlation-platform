package com.acp.enrichment.observability;

import com.acp.enrichment.pipeline.EnrichmentPipeline;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases self-clear held raises whose hold-time has elapsed un-cleared, re-injecting
 * them into the pipeline at the FlapDamp stage (design step 4: "Hold elapses with no clear, release
 * the held raise"). The sweep cadence is short relative to the smallest configured hold-time.
 *
 * <p>The per-alarm event-time release inside the pipeline flushes held raises during P2 HISTORY
 * batch-replay; this wall-clock sweep is the LIVE backstop. On shutdown a final unconditional drain
 * ({@link EnrichmentPipeline#drainSelfClearHolds()}) guarantees no held raise is left stranded
 * (Defect #4 caution).
 */
@Component
public class SelfClearSweeper {

    private final EnrichmentPipeline pipeline;

    public SelfClearSweeper(EnrichmentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${enrichment.self-clear-sweep-ms:1000}")
    public void sweep() {
        pipeline.sweepSelfClearReleases();
    }

    /** Final flush at shutdown: release every remaining held raise so none is stranded. */
    @PreDestroy
    public void drainOnShutdown() {
        pipeline.drainSelfClearHolds();
    }
}
