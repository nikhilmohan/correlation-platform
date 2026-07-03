package com.acp.patternmanager.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The FROZEN operator-edit body for {@code PATCH /patterns/{patternId}} (P2-GAP-06, SSoT):
 * {@code { sequenceFlags: [{ index, optional }], reviewer, notes? }}. Per-position {@code optional}
 * markers; {@code sessionWindow} is NOT editable (read-only in MVP, OQ-6).
 *
 * @param sequenceFlags per-position optional markers
 * @param reviewer the operator making the edit (required)
 * @param notes optional notes
 */
public record PatternEdit(
        @NotNull @Valid List<SequenceFlag> sequenceFlags,
        @NotBlank(message = "reviewer is required") String reviewer,
        String notes) {

    /**
     * A per-position optional marker.
     *
     * @param index the sequence position (>= 0)
     * @param optional whether this position is optional
     */
    public record SequenceFlag(
            @NotNull @Min(value = 0, message = "index must be >= 0") Integer index,
            @NotNull Boolean optional) {
    }
}
