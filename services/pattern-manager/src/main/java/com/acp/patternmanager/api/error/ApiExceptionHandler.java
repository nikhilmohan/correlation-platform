package com.acp.patternmanager.api.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Structured JSON error bodies ({@code {timestamp, status, error, message, patternId?}}) for the
 * HTTP surface — never a stack trace, never a silent 200 (design "Error handling").
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PatternNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(PatternNotFoundException e,
            HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), e.patternId(), req);
    }

    @ExceptionHandler(InvalidLifecycleStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(InvalidLifecycleStateException e,
            HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, e.getMessage(), e.patternId(), req);
    }

    @ExceptionHandler(UnprocessableEditException.class)
    public ResponseEntity<Map<String, Object>> unprocessable(UnprocessableEditException e,
            HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e.patternId(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e,
            HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("validation failed");
        return build(HttpStatus.UNPROCESSABLE_ENTITY, msg, null, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e,
            HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), null, req);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
            String patternId, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (patternId != null) {
            body.put("patternId", patternId);
        }
        body.put("path", req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
