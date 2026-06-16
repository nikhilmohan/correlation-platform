package com.acp.topology.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Structured error body shared by all error responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        List<Violation> violations,
        String traceId) {

    /** A single validation violation. */
    public record Violation(String path, String rule, String detail) {
    }
}
