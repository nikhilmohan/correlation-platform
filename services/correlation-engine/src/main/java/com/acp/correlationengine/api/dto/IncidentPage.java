package com.acp.correlationengine.api.dto;

import java.util.List;

/**
 * The canonical platform list-pagination envelope for {@code GET /incidents} (P3-G3) —
 * {@code { items, total, limit, offset }}, the same key set as Pattern Manager's {@code PatternPage}
 * and web-ui's {@code RunStatsPage}. Consumers read {@code .items} uniformly; never a bare array.
 *
 * @param items the incident views on this page
 * @param total the filtered count (ignoring limit/offset)
 * @param limit the echoed page size
 * @param offset the echoed page offset
 */
public record IncidentPage(
        List<IncidentView> items,
        long total,
        int limit,
        int offset) {
}
