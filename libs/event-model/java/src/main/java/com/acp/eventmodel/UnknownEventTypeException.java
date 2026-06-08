package com.acp.eventmodel;

/**
 * Raised when an envelope's {@code type} string resolves to no payload class (criterion 5).
 *
 * <p>DLQ-eligible from a consuming service's point of view.
 */
public class UnknownEventTypeException extends CodecException {

    public UnknownEventTypeException(String message) {
        super(message);
    }
}
