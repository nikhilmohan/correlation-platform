package com.acp.alarmmanager.api;

import com.acp.alarmmanager.api.dto.ApiError;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Maps query-API exceptions to the structured {@link ApiError} body with the right HTTP status. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QueryValidationException.class)
    public ResponseEntity<ApiError> handleValidation(QueryValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError(
                400, "BAD_REQUEST", e.getMessage(), traceId()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status).body(new ApiError(
                status.value(), status.name(), e.getReason(), traceId()));
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
