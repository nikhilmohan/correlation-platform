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

    /**
     * Audit reason for a status-sync STATE change that the state-precedence guard IGNORED because it
     * would have downgraded a {@code correlated} alarm (placed in a fired incident) back to a weaker
     * state via the status channel — an out-of-order lagging sibling pattern-instance event. Recorded
     * so the suppressed transition is debuggable in the {@code state_transition} audit trail; the
     * {@code lifecycle_state} is left unchanged (still {@code correlated}).
     */
    public static final String REASON_DOWNGRADE_IGNORED =
            "status-sync downgrade ignored: correlated not overwritten by weaker out-of-order state";

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
        Optional<LifecycleState> current = alarms.currentLifecycleState(alarmId);
        if (current.isEmpty()) {
            // Ordering race: the status change beat the alarm's own ingest/persist. Do NOT drop it
            // (that was the stuck-open bug) — PARK it durably keyed by alarmId; the ingest path
            // re-applies it once the alarm is persisted. Last-write-wins by changedAt.
            parkThenDrainIfRaced(alarmId, state.wire(), source, changedAt, causedByEventId, now);
            return;
        }
        // State-precedence guard (THE fix). The correlation-engine fans one alarm across MULTIPLE
        // pattern instances; a lagging sibling instance can emit an in-progress/open status event
        // milliseconds AFTER the winning instance already fired `correlated` (incident placed).
        // alarm-manager is the sole owner of the state machine, so it enforces that `correlated`
        // ("in a fired incident") is terminal-for-downgrade on the STATUS-SYNC channel, regardless
        // of event arrival order: a weaker-or-equal state never overwrites `correlated`. This is
        // ONLY reached for the {open,in-progress,correlated} status states — `clear` and the
        // expiry `revertToOpen` are their own methods and are intentionally NOT guarded here.
        if (current.get() == LifecycleState.CORRELATED
                && state.statusRank() <= LifecycleState.CORRELATED.statusRank()) {
            log.info("ignoring out-of-order status-sync downgrade for alarmId={}: {} -> {} "
                    + "(correlated is terminal-for-downgrade on the status channel)",
                    alarmId, current.get().wire(), state.wire());
            // Keep an audit trail so the suppressed transition is debuggable; state is unchanged.
            transitions.append(alarmId, LifecycleState.CORRELATED.wire(), REASON_DOWNGRADE_IGNORED,
                    source, changedAt, causedByEventId, now);
            metrics.downgradeIgnored(current.get().wire(), state.wire());
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
            metrics.clearParkedForUnknownAlarm();
            parkThenDrainIfRaced(alarmId, WIRE_CLEARED, source, clearedAt, causedByEventId, now);
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
            parkThenDrainIfRaced(alarmId, WIRE_REVERTED_OPEN, source, changedAt, causedByEventId,
                    now);
            return;
        }
        alarms.revertToOpenClearingProvisionalRole(alarmId, now);
        transitions.append(alarmId, LifecycleState.OPEN.wire(), REASON_REVERT, source, changedAt,
                causedByEventId, now);
    }

    /**
     * Park a status change whose alarm has not yet been persisted (ordering race), THEN self-heal
     * the millisecond race between this park and the alarm's persist commit.
     *
     * <p>Upsert into the durable pending-status store keyed by {@code alarmId}, last-write-wins by
     * {@code changedAt} (the state machine is monotonic toward {@code correlated}, so keeping the
     * latest is correct). Then RE-CHECK {@link AlarmRepository#exists}: the window the reviewer
     * flagged is that our {@code exists()=false} read can be immediately followed by the persist
     * transaction fully committing (insert + its own {@link #reapplyPending} finding nothing parked)
     * BEFORE our park upsert commits — which would orphan the parked row and leave the alarm stuck
     * {@code open}. If the alarm now exists, we drain the parked row ourselves via
     * {@link #reapplyPending}. Draining is race-safe against a concurrent persist-path drain: both
     * go through {@link PendingStatusRepository#claim} (atomic {@code DELETE ... RETURNING}), so
     * only ONE actor removes-and-applies the row (exactly-one correlated audit); the other is a
     * no-op. After both transactions commit in any order the alarm ends in the parked state, never
     * orphaned open.
     */
    private void parkThenDrainIfRaced(String alarmId, String wireStatus, String source,
            Instant changedAt, String causedByEventId, Instant now) {
        log.info("status '{}' for not-yet-persisted alarmId={} — parking for re-apply on ingest",
                wireStatus, alarmId);
        pending.upsert(new PendingStatus(alarmId, wireStatus, source, changedAt, causedByEventId,
                now));
        metrics.statusParkedForUnknownAlarm();
        if (alarms.exists(alarmId)) {
            // The alarm was persisted during the park window. The persist-path reapply may have run
            // before our row was visible and found nothing — so drain it ourselves now. claim() is
            // atomic, so if the persist path is also draining concurrently only one of us wins.
            log.info("alarmId={} persisted during park window — draining parked '{}' now", alarmId,
                    wireStatus);
            reapplyPending(alarmId, now);
        }
    }

    /**
     * Re-apply a parked status change for an alarm that has just been persisted, then delete the
     * parked entry. Called from the ingest transaction (see {@link AlarmPersister#persistOpen}) so a
     * freshly-persisted alarm never lingers {@code open} when a {@code correlated} status already
     * arrived ahead of it, and also from {@link #parkThenDrainIfRaced} to self-heal the park-vs-
     * persist race window.
     *
     * <p>Claims the parked row with an atomic {@link PendingStatusRepository#claim} ({@code DELETE
     * ... RETURNING}) so that when both drain paths race the same row, exactly ONE removes-and-
     * returns it and therefore exactly one appends the audit entry (no double-count); the loser gets
     * empty and no-ops. Replays through the very same state-machine methods the live status change
     * would have used ({@link #applyState}/{@link #clear}/{@link #revertToOpen}) — so all
     * state-machine and audit rules are reused. The alarm now exists, so those methods take the
     * apply branch (never re-park). Applying is idempotent: e.g. {@code correlated} on an
     * already-{@code correlated} alarm is a harmless no-op write. A no-op when nothing is parked.
     */
    @Transactional
    public void reapplyPending(String alarmId, Instant now) {
        Optional<PendingStatus> claimed = pending.claim(alarmId);
        if (claimed.isEmpty()) {
            return;
        }
        PendingStatus p = claimed.get();
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
    }
}
