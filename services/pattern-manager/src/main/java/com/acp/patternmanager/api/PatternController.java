package com.acp.patternmanager.api;

import com.acp.patternmanager.api.dto.ApprovalIntent;
import com.acp.patternmanager.api.dto.DeprecateIntent;
import com.acp.patternmanager.api.dto.PatternEdit;
import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Pattern Manager HTTP surface (OpenAPI 3.1 at {@code /openapi.json}). Serves the web-ui's
 * pattern-review/XAI module and the Correlation Engine's approved-pattern read. Response shapes are
 * the frozen SSoT: {@link PatternPage} envelope for list, {@link PatternView} for get, the frozen
 * {@link PatternEdit} PATCH body.
 */
@RestController
@RequestMapping("/patterns")
public class PatternController {

    private final PatternQueryService queryService;
    private final LifecycleService lifecycleService;
    private final PatternEditService editService;

    public PatternController(PatternQueryService queryService, LifecycleService lifecycleService,
            PatternEditService editService) {
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.editService = editService;
    }

    @Operation(summary = "List patterns (PatternPage envelope), filterable by lifecycle")
    @GetMapping
    public PatternPage list(
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String sort) {
        return queryService.list(lifecycle, limit, offset, sort);
    }

    @Operation(summary = "Get a single pattern by id (full PatternView incl. XAI + sessionWindow)")
    @GetMapping("/{patternId}")
    public PatternView get(@PathVariable String patternId) {
        return queryService.get(patternId);
    }

    @Operation(summary = "Approve or reject a draft pattern")
    @PostMapping("/{patternId}/approve")
    public PatternView approve(@PathVariable String patternId,
            @Valid @RequestBody ApprovalIntent intent) {
        return lifecycleService.decide(patternId, intent, UUID.randomUUID().toString());
    }

    @Operation(summary = "Edit a draft pattern (per-position optional markers; frozen PatternEdit body)")
    @PatchMapping("/{patternId}")
    public PatternView edit(@PathVariable String patternId,
            @Valid @RequestBody PatternEdit edit) {
        return editService.applyEdit(patternId, edit);
    }

    @Operation(summary = "Deprecate a draft or approved pattern")
    @PostMapping("/{patternId}/deprecate")
    public PatternView deprecate(@PathVariable String patternId,
            @Valid @RequestBody DeprecateIntent intent) {
        return lifecycleService.deprecate(patternId, intent);
    }
}
