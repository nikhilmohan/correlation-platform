package com.acp.correlationengine.integration;

import com.acp.correlationengine.correlate.CorrelationEngine;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wall-clock driver for session-expiry + uncovered-buffer codebook decode — the production analogue
 * of the design's {@code ExpiryPunctuator}. On each tick it advances the engine's clock so due
 * instances are destroyed (revert-open) and the per-trail uncovered buffers are decoded. The cadence
 * is env-configurable ({@code correlation-engine.expiry-tick-ms}).
 */
public class ExpiryScheduler {

    private final CorrelationEngine engine;

    public ExpiryScheduler(CorrelationEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${correlation-engine.expiry-tick-ms}")
    public void tick() {
        engine.onClockTick(Instant.now().toEpochMilli());
    }
}
