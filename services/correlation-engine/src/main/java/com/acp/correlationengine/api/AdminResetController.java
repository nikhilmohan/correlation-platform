package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.ResetResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/ops endpoint for the P3 demo reset. {@code POST /admin/reset-correlation} purges all
 * CE-owned incidents ({@code incident.incident} + {@code incident.incident_alarm}) and resets the
 * in-memory correlation session so the web-ui KPIs return to zero and no stale incidents remain. The
 * loaded P2 model (compatibility index / approved patterns / codebook / Knowledge params) survives,
 * so a subsequent run still correlates without a restart. Idempotent (a second call returns zeros,
 * 200). Contract addition — see spec.md AC and architecture.md CE API row.
 */
@RestController
public class AdminResetController {

    private final CorrelationResetService resetService;

    public AdminResetController(CorrelationResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/admin/reset-correlation")
    public ResetResult reset() {
        return resetService.reset();
    }
}
