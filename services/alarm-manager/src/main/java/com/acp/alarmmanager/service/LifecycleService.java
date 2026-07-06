package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.PendingStatus;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import java.util.Optional;
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

    /**
     * Wire {@code newStatus} values for a parked {@link PendingStatus} (mirrors
     * {@code AlarmStatusChange.NewStatus} without introducing a compile dependency on the enum in
     * the re-apply path). These are the values {@link StatusSyncService} maps from, re-used here so
     * a parked change replays through exactly the same state-machine method it would have.
     */
    static final String WIRE_OPEN = "open";
    static final String WIRE_IN_PROGRESS = "in-progress";
    static final String WIRE_CORRELATED = "correlated";
    static final String WIRE_CLEARED = "cleared";
    static final String WIRE_REVERTED_OPEN = "reverted-open";

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final AlarmRepository alarms;
    private final StateTransitionRepository transitions;
    private final PendingStatusRepository pending;
    private final AmMetrics metrics;

    public LifecycleService(AlarmRepository alarms, StateTransitionRepository transitions,
            PendingStatusRepository pending, AmMetrics metrics) {
        this.alarms = alarms;
        this.transitions = transitions;
        this.pending = pending;
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
            // Ordering race: the status change beat the alarm's own ingest/persist. Do NOT drop it
            // (that was the stuck-open bug) — PARK it durably keyed by alarmId; the ingest path
            // re-applies it once the alarm is persisted. Last-write-wins by changedAt.
            park(alarmId, state.wire(), source, changedAt, causedByEventId, now);
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
            // A status-channel clear (AlarmStatusChange(cleared)) can also race ahead of the
            // alarm's persist — park it for re-apply rather than dropping it.
            park(alarmId, WIRE_CLEARED, source, clearedAt, causedByEventId, now);
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
            // A reverted-open can also race ahead of the alarm's persist — park for re-apply.
            park(alarmId, WIRE_REVERTED_OPEN, source, changedAt, causedByEventId, now);
            return;
        }
        alarms.revertToOpenClearingProvisionalRole(alarmId, now);
        transitions.append(alarmId, LifecycleState.OPEN.wire(), REASON_REVERT, source, changedAt,
                causedByEventId, now);
    }

    /**
     * Park a status change whose alarm has not yet been persisted (ordering race). Upsert into the
     * durable pending-status store keyed by {@code alarmId}, last-write-wins by {@code changedAt}
     * (the state machine is monotonic toward {@code correlated}, so keeping the latest is correct).
     * Re-applied on the ingest path by {@link #reapplyPending}.
     */
    private void park(String alarmId, String wireStatus, String source, Instant changedAt,
            String causedByEventId, Instant now) {
        log.info("status '{}' for not-yet-persisted alarmId={} — parking for re-apply on ingest",
                wireStatus, alarmId);
        pending.upsert(new PendingStatus(alarmId, wireStatus, source, changedAt, causedByEventId,
                now));
        metrics.statusForUnknownAlarm();
    }

    /**
     * Re-apply a parked status change for an alarm that has just been persisted on the ingest path,
     * then delete the parked entry. Called from the ingest transaction (see
     * {@link AlarmPersister#persistOpen}) so a freshly-persisted alarm never lingers {@code open}
     * when a {@code correlated} status already arrived ahead of it.
     *
     * <p>Replays through the very same state-machine methods the live status change would have used
     * ({@link #applyState}/{@link #clear}/{@link #revertToOpen}) — so all state-machine and audit
     * rules are reused. The alarm now exists, so those methods take the apply branch (never
     * re-park). Applying is idempotent: e.g. {@code correlated} on an already-{@code correlated}
     * alarm is a harmless no-op write. A no-op when nothing is parked.
     */
    @Transactional
    public void reapplyPending(String alarmId, Instant now) {
        Optional<PendingStatus> parked = pending.find(alarmId);
        if (parked.isEmpty()) {
            return;
        }
        PendingStatus p = parked.get();
        log.info("re-applying parked status '{}' to freshly-persisted alarmId={}", p.newStatus(),
                alarmId);
        switch (p.newStatus()) {
            case WIRE_OPEN -> applyState(alarmId, LifecycleState.OPEN, p.source(), p.changedAt(),
                    p.causedByEventId(), now);
            case WIRE_IN_PROGRESS -> applyState(alarmId, LifecycleState.IN_PROGRESS, p.source(),
                    p.changedAt(), p.causedByEventId(), now);
            case WIRE_CORRELATED -> applyState(alarmId, LifecycleState.CORRELATED, p.source(),
                    p.changedAt(), p.causedByEventId(), now);
            case WIRE_CLEARED -> clear(alarmId, p.source(), p.changedAt(), p.causedByEventId(), now);
            case WIRE_REVERTED_OPEN -> revertToOpen(alarmId, p.source(), p.changedAt(),
                    p.causedByEventId(), now);
            default -> log.warn("parked status '{}' for alarmId={} is unrecognised — discarding",
                    p.newStatus(), alarmId);
        }
        pending.delete(alarmId);
    }
}
