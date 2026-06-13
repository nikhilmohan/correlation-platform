package com.acp.knowledge.api.dto;

import com.acp.knowledge.validation.Violation;
import java.util.List;

/**
 * The structured HTTP 422 body (the design's "Structured validation error body").
 *
 * @param error always {@code validation_failed}
 * @param recordType the recordType being written
 * @param domain the domain scope
 * @param violations the field-level violations naming each offending field/entry + rule
 */
public record ValidationErrorResponse(
        String error,
        String recordType,
        String domain,
        List<Violation> violations) {

    public static ValidationErrorResponse of(String domain, String recordType,
            List<Violation> violations) {
        return new ValidationErrorResponse("validation_failed", recordType, domain, violations);
    }
}
