package com.acp.correlationengine.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A committed incident — the system-of-record record persisted to the {@code incident} schema and
 * the source of {@code CorrelationResultEvent} + the read API.
 *
 * <p>{@code rootCauseAlarmType} is a read-model field (D2) — the canonical {@code alarmType} token
 * of the tagged root cause, persisted so {@code GET /incidents} can serve it for token-space RCA
 * accuracy without re-fetching the alarm. It is NOT on {@code CorrelationResultEvent} (read-API only).
 */
public record Incident(
        String incidentId,
        String trailId,
        String rootCauseAlarmId,
        String rootCauseAlarmType,
        List<String> childAlarmIds,
        String matchedPatternId,
        String matchedCodebookId,
        double confidence,
        MatchCandidate.MatchType matchType,
        String instanceFingerprint,
        Instant createdAt) {

    public Incident {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(trailId, "trailId");
        Objects.requireNonNull(rootCauseAlarmId, "rootCauseAlarmId");
        childAlarmIds = List.copyOf(childAlarmIds);
    }

    /** @return the read-API/wire token: {@code "pattern"} or {@code "codebook"}. */
    public String matchTypeToken() {
        return matchType == MatchCandidate.MatchType.PATTERN ? "pattern" : "codebook";
    }
}
