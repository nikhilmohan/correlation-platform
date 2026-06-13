package com.acp.knowledge.validation;

import java.util.List;

/**
 * Thrown when a write fails validation. Carries the structured violations that the API surfaces
 * as an HTTP 422 body. No write is ever opened when this is thrown (validation precedes the
 * single transaction).
 */
public class ValidationException extends RuntimeException {

    private final transient String domain;
    private final transient String recordType;
    private final transient List<Violation> violations;

    public ValidationException(String domain, String recordType, List<Violation> violations) {
        super("validation_failed: " + violations);
        this.domain = domain;
        this.recordType = recordType;
        this.violations = List.copyOf(violations);
    }

    public String domain() {
        return domain;
    }

    public String recordType() {
        return recordType;
    }

    public List<Violation> violations() {
        return violations;
    }
}
