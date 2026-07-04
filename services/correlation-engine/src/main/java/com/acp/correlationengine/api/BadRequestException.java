package com.acp.correlationengine.api;

/** A 400 Bad Request for invalid query parameters (bad {@code matchType} or date). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
