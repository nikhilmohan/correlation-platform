package com.acp.enrichment.pipeline;

/**
 * The runtime path an alarm entered on. Set at the listener; drives the output topic and the DLQ
 * choice (design "Output routing" — a type-safe {@code Path} enum, not a mutable header).
 */
public enum Path {
    /** Consumed from {@code alarms.history}; survivors emitted on {@code alarms.enriched}. */
    HISTORY,
    /** Consumed from {@code alarms.live}; survivors emitted on {@code alarms.enriched.live}. */
    LIVE
}
