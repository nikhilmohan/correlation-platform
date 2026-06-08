package com.acp.eventmodel;

/** Raised when a {@code managedObjectId} string is not well-formed (criteria 7, 16). */
public class ManagedObjectIdException extends CodecException {

    public ManagedObjectIdException(String message) {
        super(message);
    }
}
