package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.IncidentPage;
import com.acp.correlationengine.api.dto.IncidentView;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository.IncidentFilter;
import com.acp.correlationengine.model.Incident;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API for the web-ui Correlation Stats module (spec Task 10). {@code GET /incidents} returns
 * the canonical {@code IncidentPage} envelope {@code { items, total, limit, offset }} (P3-G3, AC24);
 * {@code GET /incidents/{incidentId}} returns a single {@link IncidentView} incl.
 * {@code rootCauseAlarmType} (AC18/AC29). All external access to the Incident Store is through here.
 */
@RestController
public class IncidentQueryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> MATCH_TYPES = Set.of("pattern", "codebook");

    private final IncidentRepository repository;

    public IncidentQueryController(IncidentRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/incidents")
    public IncidentPage list(
            @RequestParam(required = false) String trailId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String matchType,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        if (matchType != null && !MATCH_TYPES.contains(matchType)) {
            throw new BadRequestException("matchType must be one of pattern, codebook");
        }
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        int effectiveLimit = clampLimit(limit);
        int effectiveOffset = Math.max(0, offset);

        IncidentFilter filter = new IncidentFilter(
                trailId, fromInstant, toInstant, matchType, effectiveLimit, effectiveOffset);
        List<Incident> incidents = repository.find(filter);
        long total = repository.count(filter);
        List<IncidentView> items = incidents.stream().map(IncidentView::from).toList();
        return new IncidentPage(items, total, effectiveLimit, effectiveOffset);
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentView> byId(@PathVariable String incidentId) {
        return repository.findById(incidentId)
                .map(IncidentView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be an ISO-8601 UTC timestamp");
        }
    }
}
