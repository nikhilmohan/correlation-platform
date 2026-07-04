package com.acp.alarmmanager.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Structured error body shared by the query API error responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        String traceId) {
}
