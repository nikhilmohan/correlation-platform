package com.acp.correlationengine.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reference model of one approved pattern, as it is placed on a trail for
 * {@code (trailId, patternId)} instance keying.
 *
 * <p>The {@code trailId} is taken from the Pattern Manager read API's {@code PatternView.trailId}
 * (the frozen {@code PatternApprovedEvent} carries no {@code trailId}; a {@code patterns.approved}
 * event is only a refresh trigger — spec Task 1 / AC27). The per-pattern {@code sessionWindow}
 * ({@code windowMs}, {@code type}) is likewise from {@code PatternView} — it governs each
 * correlation instance's lifetime and is NOT sourced from the Knowledge Service.
 *
 * <p>{@code sampleAlarmObjectTypes} maps each witnessed {@code alarmType} to the {@code objectType}
 * that raised it, derived from {@code PatternView.sampleAlarms[].managedObjectId} prefixes. It is the
 * self-contained source of the pattern's required {@code objectType}s for structural compatibility —
 * no affinity table, no Knowledge dependency, no contract change (spec OQ-G2 resolved). May be empty
 * for patterns whose read model carries no sample alarms; the discovery-trail members then serve as
 * the fallback witness (see {@code RequiredObjectTypesResolver}).
 */
public record PatternRef(
        String patternId,
        String trailId,
        List<String> sequence,
        String rootCauseAlarmType,
        double confidence,
        long windowMs,
        WindowType windowType,
        Map<String, String> sampleAlarmObjectTypes) {

    public PatternRef {
        Objects.requireNonNull(patternId, "patternId");
        Objects.requireNonNull(trailId, "trailId");
        sequence = sequence == null ? List.of() : List.copyOf(sequence);
        Objects.requireNonNull(rootCauseAlarmType, "rootCauseAlarmType");
        Objects.requireNonNull(windowType, "windowType");
        sampleAlarmObjectTypes = sampleAlarmObjectTypes == null
                ? Map.of() : Map.copyOf(sampleAlarmObjectTypes);
    }

    /**
     * Backward-compatible constructor (no sample-alarm object types). Existing call sites that
     * predate pattern generalization construct a {@link PatternRef} with an empty affinity map; such
     * a pattern generalizes only if its object types can be resolved from the discovery-trail members
     * fallback, otherwise it is excluded fail-safe.
     */
    public PatternRef(String patternId, String trailId, List<String> sequence,
            String rootCauseAlarmType, double confidence, long windowMs, WindowType windowType) {
        this(patternId, trailId, sequence, rootCauseAlarmType, confidence, windowMs, windowType,
                Map.of());
    }

    /** @return the alarm type that opens (lazily creates) an instance for this pattern. */
    public String openingAlarmType() {
        return sequence.isEmpty() ? rootCauseAlarmType : sequence.get(0);
    }

    /**
     * @return the discovery trail the pattern was mined on (provenance). This is the same value as
     *     {@link #trailId()} (from {@code PatternView.trailId}); it is exposed under a distinct
     *     accessor because, under pattern generalization, {@code trailId} is no longer the runtime
     *     matching key — the compatibility index drives fan-out — so the discovery trail is retained
     *     purely as immutable provenance carried onto the incident (spec Task NEW / AC35 / AC44).
     */
    public String discoveryTrailId() {
        return trailId;
    }
}
