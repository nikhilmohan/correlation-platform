package com.acp.knowledge.domain;

/** Thrown when a requested record / version / domain does not exist (HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
