package com.acp.topology.ingest;

import com.acp.topology.api.dto.ApiError.Violation;
import java.util.List;

/** Raised when a snapshot file fails structural or semantic validation (yields HTTP 422). */
public class ValidationException extends RuntimeException {

    private final transient List<Violation> violations;

    public ValidationException(String message, List<Violation> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
