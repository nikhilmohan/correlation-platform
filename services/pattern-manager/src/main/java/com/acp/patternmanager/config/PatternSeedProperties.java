package com.acp.patternmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound configuration for the pre-approved pattern seed pack (mirrors Knowledge's
 * {@code knowledge.seed.*}). The seed lets a fresh deploy run P3 correlation immediately without
 * first running the resource-heavy pattern-miner: on startup, {@code PatternSeedLoader} loads a set
 * of already-{@code approved} Core IP cascade patterns into the Pattern Store (idempotently — safe to
 * re-run). Running the miner later refreshes/augments the store as normal.
 *
 * <p>Env-overridable, no hard-coded magic values in code:
 * <ul>
 *   <li>{@code on-startup} ({@code PATTERN_SEED_ON_STARTUP}, default {@code true}) — master switch.</li>
 *   <li>{@code pack} ({@code PATTERN_SEED_PACK}, default {@code seed/core-ip-patterns.json}) — the
 *       classpath resource of the seed pack to load.</li>
 *   <li>{@code emit-approved-events} ({@code PATTERN_SEED_EMIT_APPROVED_EVENTS}, default
 *       {@code true}) — whether to emit a {@code PatternApprovedEvent} for each newly seeded pattern
 *       (so a Correlation Engine already running when the seed loads picks it up via
 *       {@code patterns.approved}; on a cold start CE reads the approved set from the read API
 *       regardless).</li>
 * </ul>
 *
 * @param onStartup master switch for the seed loader
 * @param pack the classpath resource path of the seed pack
 * @param emitApprovedEvents whether to emit {@code PatternApprovedEvent} per newly seeded pattern
 */
@ConfigurationProperties(prefix = "pattern-manager.seed")
public record PatternSeedProperties(Boolean onStartup, String pack, Boolean emitApprovedEvents) {

    /** Documented defaults applied when unset — no hard-coded values buried in code. */
    public PatternSeedProperties {
        onStartup = onStartup == null ? Boolean.TRUE : onStartup;
        pack = (pack == null || pack.isBlank()) ? "seed/core-ip-patterns.json" : pack;
        emitApprovedEvents = emitApprovedEvents == null ? Boolean.TRUE : emitApprovedEvents;
    }
}
