package com.acp.patternmanager.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body for {@code POST /patterns/{patternId}/deprecate}.
 *
 * @param reviewer the operator deprecating the pattern (required)
 * @param notes optional notes
 */
public record DeprecateIntent(
        @NotBlank(message = "reviewer is required")
        String reviewer,
        String notes) {
}
