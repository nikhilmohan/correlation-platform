package com.acp.alarmmanager.api;

import com.acp.alarmmanager.api.dto.AlarmDetail;
import com.acp.alarmmanager.api.dto.AlarmPage;
import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.AlarmQueryFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The live alarm query API consumed by the web-ui live/incident views. */
@RestController
@RequestMapping(path = "/alarms", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlarmQueryController {

    private final AlarmQueryService queryService;
    private final AlarmManagerProperties.Query cfg;

    public AlarmQueryController(AlarmQueryService queryService, AlarmManagerProperties properties) {
        this.queryService = queryService;
        this.cfg = properties.getQuery();
    }

    @Operation(summary = "List/filter live alarms (paginated).",
            description = "Returns the platform-canonical { items, total, limit, offset } "
                    + "list-pagination envelope. Filters are AND-combined.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Canonical AlarmPage envelope.",
                    content = @Content(schema = @Schema(implementation = AlarmPage.class))),
            @ApiResponse(responseCode = "400", description = "Invalid state enum / from-to.")
    })
    @GetMapping
    public AlarmPage list(
            @Parameter(description = "Lifecycle STATE filter.",
                    schema = @Schema(allowableValues = {"open", "in-progress", "correlated", "cleared"}))
            @RequestParam(required = false) String state,
            @Parameter(description = "Only alarms whose trailIds contain this value.")
            @RequestParam(required = false) String trailId,
            @Parameter(description = "Only alarms linked to this incident.")
            @RequestParam(required = false) String incidentId,
            @Parameter(description = "raisedAt at or after this ISO-8601 UTC time.")
            @RequestParam(required = false) String from,
            @Parameter(description = "raisedAt at or before this ISO-8601 UTC time.")
            @RequestParam(required = false) String to,
            @Parameter(description = "Page size (default from config, capped).")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Rows to skip.")
            @RequestParam(required = false) Integer offset) {

        LifecycleState stateFilter = parseState(state);
        Instant fromInstant = parseTime(from, "from");
        Instant toInstant = parseTime(to, "to");
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new QueryValidationException("'from' must not be after 'to'");
        }
        int effectiveLimit = clampLimit(limit);
        int effectiveOffset = clampOffset(offset);

        AlarmQueryFilter filter = new AlarmQueryFilter(stateFilter, trailId, incidentId,
                fromInstant, toInstant, effectiveLimit, effectiveOffset);
        return queryService.list(filter);
    }

    @Operation(summary = "Retrieve a single alarm's full record with ordered transition history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full AlarmDetail record.",
                    content = @Content(schema = @Schema(implementation = AlarmDetail.class))),
            @ApiResponse(responseCode = "404", description = "Unknown alarmId.")
    })
    @GetMapping("/{alarmId}")
    public ResponseEntity<AlarmDetail> get(@PathVariable String alarmId) {
        return queryService.findById(alarmId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "unknown alarmId '" + alarmId + "'"));
    }

    private LifecycleState parseState(String state) {
        if (state == null) {
            return null;
        }
        try {
            return LifecycleState.fromWire(state);
        } catch (IllegalArgumentException e) {
            throw new QueryValidationException("invalid state '" + state
                    + "': expected one of open / in-progress / correlated / cleared");
        }
    }

    private Instant parseTime(String value, String param) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new QueryValidationException(
                    "invalid '" + param + "': expected ISO-8601 UTC date-time (e.g. 2026-06-13T09:00:00Z)");
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return cfg.getDefaultPageSize();
        }
        if (limit < 0) {
            throw new QueryValidationException("'limit' must not be negative");
        }
        return Math.min(limit, cfg.getMaxPageSize());
    }

    private int clampOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new QueryValidationException("'offset' must not be negative");
        }
        return offset;
    }
}
