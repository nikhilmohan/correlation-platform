package com.acp.alarmmanager.api;

/** Thrown for an invalid {@code GET /alarms} query request (mapped to HTTP 400). */
public class QueryValidationException extends RuntimeException {
    public QueryValidationException(String message) {
        super(message);
    }
}
