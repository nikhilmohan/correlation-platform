package com.acp.enrichment.trail;

/**
 * Raised when the Trail Builder {@code getTrailsForObject} lookup cannot be completed after the
 * configured retries (the circuit is open or the call kept failing). The pipeline routes the alarm
 * to the input topic's DLQ rather than emitting it with empty {@code trailIds} or dropping it
 * silently (design open question #42: retry-then-DLQ).
 */
public class TrailLookupException extends RuntimeException {

    public TrailLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
