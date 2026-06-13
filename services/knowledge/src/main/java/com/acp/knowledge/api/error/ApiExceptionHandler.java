package com.acp.knowledge.api.error;

import com.acp.knowledge.api.dto.ValidationErrorResponse;
import com.acp.knowledge.domain.NotFoundException;
import com.acp.knowledge.validation.ValidationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to the structured HTTP responses the spec mandates:
 * validation failures → 422 with field-level violations; not-found → 404; bad request → 400.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Validation failure → 422 with structured violations naming each offending field. */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ValidationErrorResponse> onValidation(ValidationException ex) {
        log.warn("validation_failed domain={} recordType={} violations={}",
                ex.domain(), ex.recordType(), ex.violations());
        return ResponseEntity.unprocessableEntity()
                .body(ValidationErrorResponse.of(ex.domain(), ex.recordType(), ex.violations()));
    }

    /** Unknown record / version / domain → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> onNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", ex.getMessage()));
    }

    /** Unknown recordType path segment / malformed input → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", ex.getMessage()));
    }
}
