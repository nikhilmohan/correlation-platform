package com.acp.enrichment.ruleset;

import java.time.Duration;
import java.util.List;

/**
 * The per-source filter parameters consumed by the fixed filter stages (design Config model). All
 * thresholds are per-source and Enrichment-owned — never hard-coded, never sourced from Knowledge.
 *
 * @param dedupWindow window within which identical {@code (managedObjectId, eventType)} alarms are
 *     count-collapsed
 * @param selfClearHoldTime hold-time for self-clear suppression of transients
 * @param flapN oscillation count threshold; a burst that exceeds N within {@code flapWindow} is damped
 * @param flapWindow flap-detection window
 * @param chatterList the per-source known-chatter list (effective = base YAML + overlay)
 */
public record FilterParams(Duration dedupWindow, Duration selfClearHoldTime, int flapN,
        Duration flapWindow, List<ChatterEntry> chatterList) {

    public FilterParams {
        chatterList = chatterList == null ? List.of() : List.copyOf(chatterList);
    }

    /** @return a copy of this params with the chatter list replaced (used by the overlay merge). */
    public FilterParams withChatterList(List<ChatterEntry> newChatterList) {
        return new FilterParams(dedupWindow, selfClearHoldTime, flapN, flapWindow, newChatterList);
    }
}
