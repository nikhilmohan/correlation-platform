package com.acp.topology.api;

import com.acp.topology.api.dto.ApiError;
import com.acp.topology.graph.GraphAccessException;
import com.acp.topology.ingest.ValidationException;
import com.acp.topology.integration.VocabularyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Maps exceptions to the structured {@link ApiError} body with the right HTTP status. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(ValidationException e) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(
                422, "UNPROCESSABLE_ENTITY", e.getMessage(), e.getViolations(), traceId()));
    }

    @ExceptionHandler(VocabularyUnavailableException.class)
    public ResponseEntity<ApiError> handleVocabUnavailable(VocabularyUnavailableException e) {
        log.error("domain vocabulary unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                502, "BAD_GATEWAY", e.getMessage(), null, traceId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(
                422, "UNPROCESSABLE_ENTITY", "request body is not readable JSON", null, traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(new ApiError(
                400, "BAD_REQUEST", e.getMessage(), null, traceId()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        return ResponseEntity.status(e.getStatusCode()).body(new ApiError(
                code, e.getStatusCode().toString(), e.getReason(), null, traceId()));
    }

    @ExceptionHandler(GraphAccessException.class)
    public ResponseEntity<ApiError> handleGraph(GraphAccessException e) {
        // Never leak NebulaGraph internals (host/space/nGQL) in the body (AC-19 / EH-10).
        log.error("graph access error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                500, "INTERNAL_SERVER_ERROR", "topology persistence error", null, traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                500, "INTERNAL_SERVER_ERROR", "unexpected error", null, traceId()));
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
