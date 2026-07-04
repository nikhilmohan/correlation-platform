package com.acp.alarmmanager.repository;

import com.acp.alarmmanager.domain.LifecycleState;
import java.time.Instant;

/**
 * Filter criteria for the {@code GET /alarms} list query (all optional, AND-combined) plus paging.
 * {@code trailId} is a membership test against the {@code trail_ids} jsonb array.
 */
public record AlarmQueryFilter(
        LifecycleState state,
        String trailId,
        String incidentId,
        Instant from,
        Instant to,
        int limit,
        int offset) {
}
