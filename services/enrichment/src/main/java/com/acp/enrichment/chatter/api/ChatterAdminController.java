package com.acp.enrichment.chatter.api;

import com.acp.enrichment.chatter.ChatterService;
import com.acp.enrichment.chatter.ChatterService.ChatterEditException;
import com.acp.enrichment.chatter.ChatterService.ChatterValidationException;
import com.acp.enrichment.ruleset.ChatterEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chatter-management API — Enrichment's first published HTTP business surface (springdoc OpenAPI
 * 3.1 at {@code /openapi.json}; design "API contracts / API schema"). The operator-mediated
 * promote/manage surface of the noise→live chatter loop: list, add (promote an NF-observed
 * signature), remove. Every successful write persists to the chatter overlay and hot-applies via an
 * atomic registry swap — durable and live with no restart (criteria 18, 19, 20). This controller
 * never calls the Noise Filter; promotion is operator-mediated via the web-ui.
 */
@RestController
@RequestMapping("/api/v1/sources/{source}/chatter")
@Tag(name = "chatter", description = "Per-source known-chatter list management (promote/list/remove)")
public class ChatterAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChatterAdminController.class);

    private final ChatterService chatterService;

    public ChatterAdminController(ChatterService chatterService) {
        this.chatterService = chatterService;
    }

    @GetMapping
    @Operation(summary = "List a source's effective chatter entries")
    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = ChatterListResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown source",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ChatterListResponse list(@PathVariable String source) {
        List<ChatterEntryDto> entries = chatterService.list(source).stream()
                .map(ChatterEntryDto::fromDomain)
                .toList();
        return ChatterListResponse.of(source, entries);
    }

    @PostMapping
    @Operation(summary = "Add (promote) a chatter entry; persists + hot-applies live")
    @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = ChatterEntryDto.class)))
    @ApiResponse(responseCode = "400", description = "Malformed entry",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown source",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Entry already present",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ChatterEntryDto> add(@PathVariable String source,
            @RequestBody ChatterEntryDto body) {
        ChatterEntry stored = chatterService.add(source, body.toDomain());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatterEntryDto.fromDomain(stored));
    }

    @DeleteMapping
    @Operation(summary = "Remove a chatter entry; persists + hot-applies live")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "400", description = "Malformed entry",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown source or entry not present",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> remove(@PathVariable String source,
            @RequestBody ChatterEntryDto body) {
        chatterService.remove(source, body.toDomain());
        return ResponseEntity.noContent().build();
    }

    // ---- error mapping -----------------------------------------------------------------------

    @ExceptionHandler(ChatterValidationException.class)
    public ResponseEntity<ApiError> onValidation(ChatterValidationException e) {
        HttpStatus status = switch (e.code()) {
            case "unknown_source", "entry_not_present" -> HttpStatus.NOT_FOUND;
            case "duplicate_entry" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(ChatterEditException.class)
    public ResponseEntity<ApiError> onEditFailure(ChatterEditException e) {
        log.error("chatter edit failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("chatter_edit_failed", e.getMessage()));
    }
}
