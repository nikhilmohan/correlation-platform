package com.acp.alarmmanager.domain;

import java.time.Instant;

/**
 * A parked status change awaiting the arrival of its alarm (ordering-race fix). On the P3 live
 * path the Correlation Engine can fire an {@code AlarmStatusChange} (e.g. {@code correlated})
 * before the Alarm Manager has persisted the referenced alarm from {@code alarms.enriched.live}.
 * Rather than dropping that status change (which left the alarm stuck {@code open}), it is durably
 * parked keyed by {@code alarmId} and re-applied by the ingest path once the alarm is persisted.
 *
 * <p>One row per {@code alarmId} (last-write-wins by {@code changedAt}); {@code newStatus} is the
 * wire status value from {@code AlarmStatusChange.newStatus}
 * ({@code open}/{@code in-progress}/{@code correlated}/{@code cleared}/{@code reverted-open}).
 */
public record PendingStatus(
        String alarmId,
        String newStatus,
        String source,
        Instant changedAt,
        String causedByEventId,
        Instant receivedAt) {
}
