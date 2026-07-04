package com.acp.correlationengine.model;

import java.util.List;
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
 */
public record PatternRef(
        String patternId,
        String trailId,
        List<String> sequence,
        String rootCauseAlarmType,
        double confidence,
        long windowMs,
        WindowType windowType) {

    public PatternRef {
        Objects.requireNonNull(patternId, "patternId");
        Objects.requireNonNull(trailId, "trailId");
        sequence = sequence == null ? List.of() : List.copyOf(sequence);
        Objects.requireNonNull(rootCauseAlarmType, "rootCauseAlarmType");
        Objects.requireNonNull(windowType, "windowType");
    }

    /** @return the alarm type that opens (lazily creates) an instance for this pattern. */
    public String openingAlarmType() {
        return sequence.isEmpty() ? rootCauseAlarmType : sequence.get(0);
    }
}
