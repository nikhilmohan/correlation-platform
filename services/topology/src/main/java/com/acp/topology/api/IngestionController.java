package com.acp.topology.api;

import com.acp.topology.api.dto.ApiError;
import com.acp.topology.api.dto.SnapshotIngestResponse;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.ingest.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ingestion API (Task 1). {@code POST /topology/snapshots} accepts a topology snapshot file
 * (application/json), validates it, lifts it, mints a {@code snapshotId}, and emits
 * {@code topology.changed} — all synchronously, returning the frozen 200 {@link SnapshotIngestResponse}
 * (P1-G1). NebulaGraph internals are never exposed.
 */
@RestController
@RequestMapping("/topology")
public class IngestionController {

    private final IngestionService ingestionService;
    private final long maxFileBytes;

    public IngestionController(IngestionService ingestionService, TopologyProperties properties) {
        this.ingestionService = ingestionService;
        this.maxFileBytes = properties.getIngest().getMaxFileBytes();
    }

    @PostMapping(path = "/snapshots", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload a topology snapshot file (synchronous lift; mints snapshotId).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lifted + persisted; event emitted.",
                    content = @Content(schema = @Schema(implementation = SnapshotIngestResponse.class))),
            @ApiResponse(responseCode = "413", description = "Body exceeds the max snapshot size.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Schema- or semantic-invalid file.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Domain vocabulary unavailable.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public ResponseEntity<SnapshotIngestResponse> ingest(
            @RequestBody String body,
            @RequestParam(name = "changeType", required = false) String changeType,
            @RequestHeader(name = "X-Trace-Id", required = false) String traceId) {
        if (body != null && body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > maxFileBytes) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "snapshot file exceeds the configured max size");
        }
        String trace = (traceId == null || traceId.isBlank())
                ? java.util.UUID.randomUUID().toString() : traceId;
        MDC.put("traceId", trace);
        try {
            SnapshotIngestResponse response = ingestionService.ingest(body, changeType, trace);
            MDC.put("snapshotId", response.snapshotId());
            return ResponseEntity.ok(response);
        } finally {
            MDC.remove("snapshotId");
            MDC.remove("traceId");
        }
    }
}
