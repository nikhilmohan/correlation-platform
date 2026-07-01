package com.acp.patternmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The lightweight approval-intent body for {@code POST /patterns/{patternId}/approve}.
 *
 * @param decision {@code approve} or {@code reject}
 * @param reviewer the operator making the decision (required)
 * @param notes optional review notes
 */
public record ApprovalIntent(
        @NotNull @Pattern(regexp = "approve|reject", message = "decision must be approve or reject")
        String decision,
        @NotBlank(message = "reviewer is required")
        String reviewer,
        String notes) {
}
