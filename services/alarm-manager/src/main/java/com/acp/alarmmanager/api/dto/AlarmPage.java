package com.acp.alarmmanager.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The platform-canonical list-pagination envelope {@code { items, total, limit, offset }} (P3-G3)
 * — the SAME envelope the Correlation Engine {@code GET /incidents} and the Pattern Manager
 * {@code GET /patterns} return, so the web-ui streaming view reads one uniform shape
 * ({@code .items} / {@code .total} / {@code .limit} / {@code .offset}) across every polled
 * endpoint. It is NOT the Spring {@code Page} shape ({@code page}/{@code size}/{@code
 * totalElements}/{@code totalPages}) and is NOT a bare array.
 *
 * @param items  the page of matching {@link AlarmSummary}
 * @param total  the full filtered count (for the streaming/progress view)
 * @param limit  page size echoed from the request
 * @param offset row offset echoed from the request
 */
@Schema(description = "Canonical list-pagination envelope { items, total, limit, offset }.")
public record AlarmPage(
        List<AlarmSummary> items,
        long total,
        int limit,
        int offset) {
}
