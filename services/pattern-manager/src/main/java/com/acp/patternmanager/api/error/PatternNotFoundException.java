package com.acp.patternmanager.api.error;

/** Thrown when a {@code patternId} does not exist -> HTTP 404. */
public class PatternNotFoundException extends RuntimeException {

    private final String patternId;

    public PatternNotFoundException(String patternId) {
        super("pattern not found: " + patternId);
        this.patternId = patternId;
    }

    public String patternId() {
        return patternId;
    }
}
