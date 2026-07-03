package com.acp.patternmanager.store;

import java.util.UUID;

/**
 * [ANCHOR-CONSOL] The result of consolidating one enriched mined event into the Pattern Store.
 *
 * @param patternId the pattern-store identity the event mapped to (anchor identity for anchored
 *     patterns, per-event identity for unexplained ones)
 * @param created {@code true} iff this event CREATED the pattern row (the first contributor for that
 *     identity). {@code PatternDiscoveredEvent} is emitted only when {@code created} is true — a
 *     later sub-run folding into an existing anchored row emits nothing (emit-once-per-identity).
 * @param folded {@code true} iff this event was a NEW contributor that aggregated into an existing
 *     anchored row (occurrences/support recomputed). {@code false} for a create or a replayed no-op.
 */
public record ConsolidationOutcome(UUID patternId, boolean created, boolean folded) {

    public static ConsolidationOutcome created(UUID patternId) {
        return new ConsolidationOutcome(patternId, true, false);
    }

    public static ConsolidationOutcome folded(UUID patternId) {
        return new ConsolidationOutcome(patternId, false, true);
    }

    /** A replayed/duplicate contributing event that was a no-op (no create, no fold). */
    public static ConsolidationOutcome noop(UUID patternId) {
        return new ConsolidationOutcome(patternId, false, false);
    }
}
