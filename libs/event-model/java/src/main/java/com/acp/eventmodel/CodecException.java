package com.acp.eventmodel;

/**
 * Raised when an envelope cannot be (de)serialized per the contract: malformed JSON,
 * missing/extra envelope fields, a payload that fails its schema (required field, enum,
 * {@code managedObjectId} pattern, unknown field), an unsupported {@code schemaVersion}, or an
 * unknown {@code type}.
 *
 * <p>This is the supertype of {@link SchemaVersionException} and {@link UnknownEventTypeException}
 * so a consuming service can catch a single type to decide "route to {@code <topic>.dlq}".
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
