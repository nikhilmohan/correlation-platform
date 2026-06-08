package com.acp.eventmodel;

/**
 * Raised when an envelope's {@code schemaVersion} major is not supported (i.e. {@code >= 2}).
 *
 * <p>A consuming service treats this as the signal to route a poison message to
 * {@code <topic>.dlq} — the library itself has no Kafka/DLQ behaviour.
 */
public class SchemaVersionException extends CodecException {

    public SchemaVersionException(String message) {
        super(message);
    }
}
