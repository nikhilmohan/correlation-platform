package com.acp.patternmanager.api.dto;

import java.util.List;

/**
 * The frozen {@code GET /patterns} envelope (P2-GAP-08, SSoT) — an object, NEVER a bare array.
 * Consumers read {@code .items} plus {@code .total}/{@code .limit}/{@code .offset}.
 *
 * @param items the page of patterns
 * @param total total patterns matching the filter (for review progress)
 * @param limit echoed page size
 * @param offset echoed page offset
 */
public record PatternPage(List<PatternView> items, long total, int limit, int offset) {
}
