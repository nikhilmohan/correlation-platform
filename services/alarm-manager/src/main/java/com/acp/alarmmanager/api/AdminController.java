package com.acp.alarmmanager.api;

import com.acp.alarmmanager.api.dto.PurgeSummary;
import com.acp.alarmmanager.service.PurgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/ops endpoints for the Alarm Manager. Scoped under {@code /admin} to keep the
 * demo/ops reset distinct from the primary {@code /alarms} query surface consumed by web-ui.
 */
@RestController
@RequestMapping(path = "/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {

    private final PurgeService purgeService;

    public AdminController(PurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Operation(summary = "Purge ALL P3 live-alarm state (demo/ops reset).",
            description = "Deletes every row from the Alarm Manager's own live_alarm schema "
                    + "(alarm, state_transition, pending_status, processed_event) in FK-safe order, "
                    + "so the web-ui topology returns to all-green. P3-only: does NOT touch P1 "
                    + "(topology) or P2 (noise-filter/patterns/codebook/knowledge) or incident data. "
                    + "Idempotent — a second call on an empty store returns all zeros, 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-table deleted counts.",
                    content = @Content(schema = @Schema(implementation = PurgeSummary.class)))
    })
    @PostMapping("/purge-live-alarms")
    public PurgeSummary purgeLiveAlarms() {
        return purgeService.purgeLiveAlarms();
    }
}
