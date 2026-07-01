package com.acp.patternmanager.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session-window derivation parameters (design "Session-window derivation", OQ-5). These are
 * documented DERIVATION parameters (env-overridable, with the documented defaults below) — they
 * are NOT Knowledge-sourced business thresholds and NOT hard-coded magic numbers. The deriver is
 * a pure function of {@code PatternMinedEvent.timing} plus these constants; it calls no
 * collaborator.
 *
 * @param marginFactor multiplier over the observed timeframe (default {@code 1.5}) so the window
 *     is comfortably longer than the typical pattern duration
 * @param minMs lower clamp in ms (default {@code 5000}) — a window is never shorter than this
 * @param maxMs upper clamp in ms (default {@code 1800000}) — a window never exceeds this
 * @param gapFloorFactor multiple of {@code maxInterArrivalMs} the window is floored at (default
 *     {@code 2.0}) so an idle-gap timeout never closes an instance mid-pattern
 * @param cvFixedThreshold coefficient-of-variation cutoff for {@code type} selection (default
 *     {@code 0.5}); {@code cv < threshold} -> {@code fixed}, else {@code gap-based}
 * @param timingAliases optional escape-hatch alias map (default empty/identity) — maps a
 *     non-conformant producer's legacy timing keys to the canonical ms keys; the value suffix
 *     {@code :seconds} triggers a x1000 seconds-to-ms normalisation. Default empty = no aliasing.
 */
@ConfigurationProperties(prefix = "pattern-manager.session-window")
public record SessionWindowProperties(
        Double marginFactor,
        Long minMs,
        Long maxMs,
        Double gapFloorFactor,
        Double cvFixedThreshold,
        Map<String, String> timingAliases) {

    /** Canonical defaults (design table). Applied when a value is unset in config. */
    public SessionWindowProperties {
        marginFactor = marginFactor != null ? marginFactor : 1.5;
        minMs = minMs != null ? minMs : 5_000L;
        maxMs = maxMs != null ? maxMs : 1_800_000L;
        gapFloorFactor = gapFloorFactor != null ? gapFloorFactor : 2.0;
        cvFixedThreshold = cvFixedThreshold != null ? cvFixedThreshold : 0.5;
        timingAliases = timingAliases != null ? timingAliases : Map.of();
    }
}
