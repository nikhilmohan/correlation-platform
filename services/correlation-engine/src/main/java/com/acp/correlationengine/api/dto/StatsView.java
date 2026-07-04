package com.acp.correlationengine.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Aggregate counts for the web-ui Correlation Stats module ({@code GET /stats}).
 *
 * <p>Raw counts are kept ({@code totalAlarmsProcessed}, {@code totalIncidentsCreated},
 * {@code patternMatchCount}, {@code codebookMatchCount}, {@code confidenceDistribution}) — enabling
 * the alarm-reduction ratio. Adds {@code correlatedAlarmCount} (D1 — the auto-correlation-rate
 * numerator: distinct alarmIds placed into a correlated incident) and an eval-mode {@code rcaAccuracy}
 * (D2 — {@code null} in production; the field is always serialized, even when null, so consumers can
 * read it). No event-model/contract change — stats read-API fields only.
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
