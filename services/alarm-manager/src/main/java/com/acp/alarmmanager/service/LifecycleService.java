package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single owner of the lifecycle state-machine transitions. Every STATE change — the ingest
 * {@code open}, the wire-{@code cleared} path, and all {@code AlarmStatusChange}-driven
 * transitions — flows through here, so one rule set writes {@code lifecycle_state} and appends the
 * matching {@code state_transition} audit entry. It never writes {@code role}/{@code incident_id}
 * (those are the ROLE channel's).
 */
@Service
public class LifecycleService {

    /** Audit reason for the single ingest-origin {@code open} entry (partial-unique guarded). */
    public static final String REASON_INGEST = "ingest";
    public static final String REASON_STATUS_SYNC = "status-sync";
    public static final String REASON_CLEAR = "clear";
    public static final String REASON_REVERT =
            "reverted from correlation: instance expired without a match";

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final AlarmRepository alarms;
    private final StateTransitionRepository transitions;
    private final AmMetrics metrics;

    public LifecycleService(AlarmRepository alarms, StateTransitionRepository transitions,
            AmMetrics metrics) {
        this.alarms = alarms;
        this.transitions = transitions;
        this.metrics = metrics;
    }

    /**
     * Record the single ingest-origin {@code open} audit entry (called on first insert only).
     * The partial unique index makes this at-most-once per alarm.
     */
    public void recordIngestOpen(String alarmId, String causedByEventId, Instant now) {
        transitions.append(alarmId, LifecycleState.OPEN.wire(), REASON_INGEST, null, null,
                causedByEventId, now);
    }

    /**
     * Apply a STATE transition (open / in-progress / correlated) via the status channel, recording
     * {@code source}/{@code changedAt} on the audit entry.
     */
    @Transactional
    public void applyState(String alarmId, LifecycleState state, String source, Instant changedAt,
            String causedByEventId, Instant now) {
        if (!alarms.exists(alarmId)) {
            log.info("status for unknown alarmId={} (raise may arrive later) — no-op", alarmId);
            metrics.statusForUnknownAlarm();
            return;
        }
        alarms.updateLifecycleState(alarmId, state, null, now);
        transitions.append(alarmId, state.wire(), REASON_STATUS_SYNC, source, changedAt,
                causedByEventId, now);
    }

    /**
     * Clear an alarm (both the wire-{@code cleared} path and {@code AlarmStatusChange(cleared)}).
     * Sets {@code cleared_at} and appends a {@code cleared} audit entry. Unknown alarm is a no-op.
     */
    @Transactional
    public void clear(String alarmId, String source, Instant clearedAt, String causedByEventId,
            Instant now) {
        if (!alarms.exists(alarmId)) {
            log.info("clear for unknown alarmId={} — no-op", alarmId);
            metrics.clearForUnknownAlarm();
            return;
        }
        Instant effectiveClearedAt = clearedAt != null ? clearedAt : now;
        alarms.updateLifecycleState(alarmId, LifecycleState.CLEARED, effectiveClearedAt, now);
        transitions.append(alarmId, LifecycleState.CLEARED.wire(), REASON_CLEAR, source,
                clearedAt, causedByEventId, now);
        metrics.cleared();
    }

    /**
     * Revert to {@code open} (instance expired). Returns STATE to {@code open}, clears a
     * provisional in-progress role association, and records a revert-reason audit entry.
     */
    @Transactional
    public void revertToOpen(String alarmId, String source, Instant changedAt,
            String causedByEventId, Instant now) {
        if (!alarms.exists(alarmId)) {
            log.info("reverted-open for unknown alarmId={} — no-op", alarmId);
            metrics.statusForUnknownAlarm();
            return;
        }
        alarms.revertToOpenClearingProvisionalRole(alarmId, now);
        transitions.append(alarmId, LifecycleState.OPEN.wire(), REASON_REVERT, source, changedAt,
                causedByEventId, now);
    }
}
