package com.acp.patternmanager.api.error;

/** Thrown when an action is attempted on a pattern in the wrong lifecycle state -> HTTP 409. */
public class InvalidLifecycleStateException extends RuntimeException {

    private final String patternId;

    public InvalidLifecycleStateException(String patternId, String message) {
        super(message);
        this.patternId = patternId;
    }

    public String patternId() {
        return patternId;
    }
}
