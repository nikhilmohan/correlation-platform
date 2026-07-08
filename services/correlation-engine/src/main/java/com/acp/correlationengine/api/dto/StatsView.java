package com.acp.correlationengine.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Aggregate counts for the web-ui Correlation Stats module ({@code GET /stats}).
 *
 * <p><b>Scope of each field</b> (see {@code StatsAggregator} for the full rationale):
 * <ul>
 *   <li>{@code totalAlarmsProcessed}, {@code correlatedAlarmCount}, {@code totalIncidentsCreated},
 *       {@code patternMatchCount}, {@code codebookMatchCount} are all ENGINE-SESSION-scoped
 *       (in-memory, reset on restart). Keeping them same-scope makes both displayed ratios
 *       internally consistent: auto-correlation ({@code correlatedAlarmCount / totalAlarmsProcessed})
 *       stays {@code <= 1} and alarm-reduction ({@code totalAlarmsProcessed / totalIncidentsCreated})
 *       stays {@code >= 1}.
 *   <li>{@code confidenceDistribution} is the only DB-derived (all-time) field — a purely
 *       informational per-band histogram that participates in no ratio.
 *   <li>{@code rcaAccuracy} (D2) is {@code null} in production; the field is always serialized, even
 *       when null, so consumers can read it.
 * </ul>
 * No event-model/contract change — stats read-API fields only.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StatsView(
        long totalAlarmsProcessed,
        long correlatedAlarmCount,
        long totalIncidentsCreated,
        long patternMatchCount,
        long codebookMatchCount,
        Map<String, Long> confidenceDistribution,
        Double rcaAccuracy) {
}
