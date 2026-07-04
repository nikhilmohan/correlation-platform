package com.acp.correlationengine.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps API exceptions to the platform error response shape
 * {@code { timestamp, status, error, message, path }} (design → Error response shape).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException e, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), req);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
            HttpServletRequest req) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "path", req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
