package com.acp.enrichment.pipeline;

/**
 * Raised by {@link NormalizeStep} when a raw alarm cannot be normalized into a valid canonical
 * {@code AlarmEvent} (missing/invalid required field, bad {@code managedObjectId}, or an unmapped
 * alarm-type under {@code onUnmapped=dlq}). The pipeline routes the offending message to the input
 * topic's DLQ with {@link #reason()} (design Error handling).
 */
public class NormalizeException extends RuntimeException {

    private final String reason;

    public NormalizeException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** @return the DLQ reason label (e.g. {@code normalize_invalid}, {@code alarmtype_unmapped}). */
    public String reason() {
        return reason;
    }
}
