package com.acp.patternmanager.api.error;

/** Thrown for an out-of-range edit index or invalid decision/body semantics -> HTTP 422. */
public class UnprocessableEditException extends RuntimeException {

    private final String patternId;

    public UnprocessableEditException(String patternId, String message) {
        super(message);
        this.patternId = patternId;
    }

    public String patternId() {
        return patternId;
    }
}
