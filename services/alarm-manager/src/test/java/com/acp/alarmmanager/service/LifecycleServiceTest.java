package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.PendingStatus;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * AC7 — wire-cleared / status-cleared transitions the alarm to cleared with a cleared audit.
 * Ordering-race fix — a status op for a not-yet-persisted alarm PARKS the change (does not drop
 * it), and a parked change is re-applied on ingest.
 */
class LifecycleServiceTest {

    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private PendingStatusRepository pending;
    private AmMetrics metrics;
    private LifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        alarms = Mockito.mock(AlarmRepository.class);
        transitions = Mockito.mock(StateTransitionRepository.class);
        pending = Mockito.mock(PendingStatusRepository.class);
        metrics = Mockito.mock(AmMetrics.class);
        lifecycle = new LifecycleService(alarms, transitions, pending, metrics);
    }

    @Test
    void clearedEventTransitionsAlarmToClearedWithAudit() {
        when(alarms.exists("ALM-0001")).thenReturn(true);
        Instant clearedAt = Instant.parse("2026-06-13T10:00:00Z");

        lifecycle.clear("ALM-0001", "simulator", clearedAt, "evt-1", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-0001"), eq(LifecycleState.CLEARED),
                eq(clearedAt), any());
        ArgumentCaptor<String> toState = ArgumentCaptor.forClass(String.class);
        verify(transitions).append(eq("ALM-0001"), toState.capture(), any(), any(), any(), any(),
                any());
        assertThat(toState.getValue()).isEqualTo("cleared");
        verify(metrics).cleared();
    }

    @Test
    void clearForUnknownAlarmParksInsteadOfDropping() {
        when(alarms.exists("UNKNOWN")).thenReturn(false);

        lifecycle.clear("UNKNOWN", "simulator", null, "evt-1", Instant.now());

        // Not applied to a (missing) row, but parked for re-apply — not dropped.
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        ArgumentCaptor<PendingStatus> parked = ArgumentCaptor.forClass(PendingStatus.class);
        verify(pending).upsert(parked.capture());
        assertThat(parked.getValue().alarmId()).isEqualTo("UNKNOWN");
        assertThat(parked.getValue().newStatus()).isEqualTo("cleared");
        verify(metrics).clearParkedForUnknownAlarm();
    }

    /** THE bug's unit-level guard: a correlated status for a not-yet-persisted alarm is PARKED. */
    @Test
    void applyStateForUnknownAlarmParksInsteadOfDropping() {
        when(alarms.currentLifecycleState("ALM-RACE")).thenReturn(Optional.empty());
        when(alarms.exists("ALM-RACE")).thenReturn(false);
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");

        lifecycle.applyState("ALM-RACE", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        // No update against a missing row, and NOT dropped — parked keyed by alarmId.
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        ArgumentCaptor<PendingStatus> parked = ArgumentCaptor.forClass(PendingStatus.class);
        verify(pending).upsert(parked.capture());
        assertThat(parked.getValue().alarmId()).isEqualTo("ALM-RACE");
        assertThat(parked.getValue().newStatus()).isEqualTo("correlated");
        assertThat(parked.getValue().changedAt()).isEqualTo(changedAt);
        assertThat(parked.getValue().causedByEventId()).isEqualTo("evt-corr");
        verify(metrics).statusParkedForUnknownAlarm();
    }

    @Test
    void applyStateForKnownAlarmAppliesImmediatelyAndDoesNotPark() {
        when(alarms.currentLifecycleState("ALM-0001"))
                .thenReturn(Optional.of(LifecycleState.IN_PROGRESS));
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");

        lifecycle.applyState("ALM-0001", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-0001"), eq(LifecycleState.CORRELATED), isNull(),
                any());
        verify(pending, never()).upsert(any());
    }

    /** Re-apply on ingest: a parked correlated is CLAIMED (atomic delete-returning) and applied to
     * the freshly-persisted alarm. The claim removes the row, so no separate delete is issued. This
     * is the state-transition side of THE bug's fix. */
    @Test
    void reapplyPendingClaimsAndAppliesParkedCorrelated() {
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");
        when(pending.claim("ALM-RACE")).thenReturn(Optional.of(new PendingStatus(
                "ALM-RACE", "correlated", "correlation-engine", changedAt, "evt-corr",
                Instant.parse("2026-06-13T09:05:01Z"))));
        // Alarm has just been persisted (open), so the re-apply takes the apply branch and the
        // forward open->correlated transition is applied.
        when(alarms.currentLifecycleState("ALM-RACE"))
                .thenReturn(Optional.of(LifecycleState.OPEN));

        lifecycle.reapplyPending("ALM-RACE", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-RACE"), eq(LifecycleState.CORRELATED), isNull(),
                any());
        verify(transitions).append(eq("ALM-RACE"), eq("correlated"), any(),
                eq("correlation-engine"), eq(changedAt), eq("evt-corr"), any());
        // The atomic claim IS the delete — the row is gone; no second delete needed.
        verify(pending).claim("ALM-RACE");
    }

    /**
     * Drain-race guard: when a concurrent drainer already CLAIMED the parked row, this caller's
     * {@code claim} returns empty and it applies nothing — so exactly one correlated audit is
     * appended, never two.
     */
    @Test
    void reapplyPendingIsNoOpWhenAnotherDrainerAlreadyClaimed() {
        when(pending.claim("ALM-RACE")).thenReturn(Optional.empty());

        lifecycle.reapplyPending("ALM-RACE", Instant.now());

        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        verify(transitions, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reapplyPendingIsNoOpWhenNothingParked() {
        when(pending.claim("ALM-NONE")).thenReturn(Optional.empty());

        lifecycle.reapplyPending("ALM-NONE", Instant.now());

        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
    }

    /**
     * THE residual-window fix (park-then-drain-if-raced): the status parks (alarm not yet visible),
     * but the alarm is persisted DURING the park window. The re-check after upsert sees the alarm
     * now exists and drains the parked row itself via the atomic claim — so the alarm never lingers
     * orphaned open.
     */
    @Test
    void parkPathDrainsItselfWhenAlarmPersistedDuringParkWindow() {
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");
        // First currentLifecycleState()=empty (park branch); parkThenDrainIfRaced then sees
        // exists()=true (persisted during the park window) and self-drains, whose reapply re-enters
        // applyState and reads the now-persisted state (open) so open->correlated applies forward.
        when(alarms.currentLifecycleState("ALM-RACE"))
                .thenReturn(Optional.empty(), Optional.of(LifecycleState.OPEN));
        when(alarms.exists("ALM-RACE")).thenReturn(true);
        // The row this call parked is what the self-drain claims back.
        when(pending.claim("ALM-RACE")).thenReturn(Optional.of(new PendingStatus(
                "ALM-RACE", "correlated", "correlation-engine", changedAt, "evt-corr",
                Instant.parse("2026-06-13T09:05:01Z"))));

        lifecycle.applyState("ALM-RACE", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        // It parked ...
        verify(pending).upsert(any());
        // ... then self-drained: claimed and applied the correlated state to the now-existing alarm.
        verify(pending).claim("ALM-RACE");
        verify(alarms).updateLifecycleState(eq("ALM-RACE"), eq(LifecycleState.CORRELATED), isNull(),
                any());
        verify(transitions).append(eq("ALM-RACE"), eq("correlated"), any(),
                eq("correlation-engine"), eq(changedAt), eq("evt-corr"), any());
    }

    /**
     * Park path, no race: the alarm still does not exist after the upsert, so nothing is drained
     * (the persist path will drain it later). No claim, no apply.
     */
    @Test
    void parkPathLeavesRowParkedWhenAlarmStillAbsentAfterUpsert() {
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");
        when(alarms.currentLifecycleState("ALM-RACE")).thenReturn(Optional.empty());
        when(alarms.exists("ALM-RACE")).thenReturn(false);

        lifecycle.applyState("ALM-RACE", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        verify(pending).upsert(any());
        verify(pending, never()).claim(any());
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void appliesInProgressStateWithSourceAndChangedAtOnAudit() {
        when(alarms.currentLifecycleState("ALM-0001"))
                .thenReturn(Optional.of(LifecycleState.OPEN));
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");

        lifecycle.applyState("ALM-0001", LifecycleState.IN_PROGRESS, "correlation-engine",
                changedAt, "evt-2", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-0001"), eq(LifecycleState.IN_PROGRESS),
                isNull(), any());
        verify(transitions).append(eq("ALM-0001"), eq("in-progress"), any(),
                eq("correlation-engine"), eq(changedAt), eq("evt-2"), any());
    }

    // ---- State-precedence guard (THE bug fix) --------------------------------------------------

    /**
     * THE bug: a `correlated` alarm (placed in a fired incident) is clobbered back to `in-progress`
     * by a lagging sibling pattern-instance's out-of-order status-sync event. The guard IGNORES the
     * downgrade — the alarm stays `correlated`, and the suppressed transition is audited.
     */
    @Test
    void correlatedIsNotDowngradedToInProgressByOutOfOrderStatusSync() {
        when(alarms.currentLifecycleState("ALM-CHILD"))
                .thenReturn(Optional.of(LifecycleState.CORRELATED));
        Instant changedAt = Instant.parse("2026-06-13T13:06:34.426Z");

        lifecycle.applyState("ALM-CHILD", LifecycleState.IN_PROGRESS, "correlation-engine",
                changedAt, "evt-lagging", Instant.now());

        // The downgrade is NOT applied — lifecycle_state stays correlated.
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        // An audit entry records the ignored transition (state remains correlated, special reason).
        ArgumentCaptor<String> toState = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(transitions).append(eq("ALM-CHILD"), toState.capture(), reason.capture(),
                eq("correlation-engine"), eq(changedAt), eq("evt-lagging"), any());
        assertThat(toState.getValue()).isEqualTo("correlated");
        assertThat(reason.getValue()).isEqualTo(LifecycleService.REASON_DOWNGRADE_IGNORED);
        verify(metrics).downgradeIgnored("correlated", "in-progress");
    }

    /** A `correlated` alarm is not downgraded to `open` by a later status-sync event either. */
    @Test
    void correlatedIsNotDowngradedToOpenByOutOfOrderStatusSync() {
        when(alarms.currentLifecycleState("ALM-CHILD"))
                .thenReturn(Optional.of(LifecycleState.CORRELATED));
        Instant changedAt = Instant.parse("2026-06-13T13:06:35Z");

        lifecycle.applyState("ALM-CHILD", LifecycleState.OPEN, "correlation-engine", changedAt,
                "evt-lagging-open", Instant.now());

        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        verify(transitions).append(eq("ALM-CHILD"), eq("correlated"),
                eq(LifecycleService.REASON_DOWNGRADE_IGNORED), any(), any(), any(), any());
        verify(metrics).downgradeIgnored("correlated", "open");
    }

    /**
     * A `correlated -> correlated` re-apply (a redelivered / duplicate correlated status-sync event)
     * is a SILENT no-op: the state is not rewritten (still `<=` STATE-WRITE suppression), but because
     * it is NOT a genuine downgrade (same rank) it must NOT be audited as REASON_DOWNGRADE_IGNORED
     * nor increment status_downgrade_ignored_total{from=correlated,to=correlated}.
     */
    @Test
    void correlatedToCorrelatedRedeliveryIsSilentNoOpNotDowngradeIgnored() {
        when(alarms.currentLifecycleState("ALM-DUP"))
                .thenReturn(Optional.of(LifecycleState.CORRELATED));
        Instant changedAt = Instant.parse("2026-06-13T13:07:00Z");

        lifecycle.applyState("ALM-DUP", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-redelivered", Instant.now());

        // State is not rewritten (suppression still holds) ...
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        // ... and it is a SILENT no-op: no downgrade-ignored audit and no downgrade-ignored metric.
        verify(transitions, never()).append(any(), any(), any(), any(), any(), any(), any());
        verify(metrics, never()).downgradeIgnored(any(), any());
    }

    /** Forward transitions still apply: open -> in-progress -> correlated each writes. */
    @Test
    void forwardTransitionsStillApply() {
        // open -> in-progress
        when(alarms.currentLifecycleState("ALM-FWD"))
                .thenReturn(Optional.of(LifecycleState.OPEN));
        lifecycle.applyState("ALM-FWD", LifecycleState.IN_PROGRESS, "correlation-engine",
                Instant.parse("2026-06-13T09:00:00Z"), "evt-a", Instant.now());
        verify(alarms).updateLifecycleState(eq("ALM-FWD"), eq(LifecycleState.IN_PROGRESS), isNull(),
                any());

        // in-progress -> correlated
        when(alarms.currentLifecycleState("ALM-FWD"))
                .thenReturn(Optional.of(LifecycleState.IN_PROGRESS));
        lifecycle.applyState("ALM-FWD", LifecycleState.CORRELATED, "correlation-engine",
                Instant.parse("2026-06-13T09:00:01Z"), "evt-b", Instant.now());
        verify(alarms).updateLifecycleState(eq("ALM-FWD"), eq(LifecycleState.CORRELATED), isNull(),
                any());
        verify(metrics, never()).downgradeIgnored(any(), any());
    }

    /**
     * The genuine expiry-revert path (in-progress -> open for a NON-correlated alarm whose window
     * expired without a match) is a DIFFERENT method and is NOT blocked by the precedence guard.
     */
    @Test
    void genuineExpiryRevertForNonCorrelatedAlarmStillApplies() {
        when(alarms.exists("ALM-NOMATCH")).thenReturn(true);

        lifecycle.revertToOpen("ALM-NOMATCH", "correlation-engine",
                Instant.parse("2026-06-13T09:10:00Z"), "evt-expiry", Instant.now());

        // Applied via the revert path (never consulted the precedence guard / currentLifecycleState).
        verify(alarms).revertToOpenClearingProvisionalRole(eq("ALM-NOMATCH"), any());
        verify(alarms, never()).currentLifecycleState(any());
        verify(metrics, never()).downgradeIgnored(any(), any());
    }

    /** A real terminal clear following `correlated` (correlated -> cleared) STILL applies. */
    @Test
    void correlatedToClearedStillApplies() {
        when(alarms.exists("ALM-CLR")).thenReturn(true);
        Instant clearedAt = Instant.parse("2026-06-13T09:20:00Z");

        lifecycle.clear("ALM-CLR", "simulator", clearedAt, "evt-clear", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-CLR"), eq(LifecycleState.CLEARED), eq(clearedAt),
                any());
        verify(alarms, never()).currentLifecycleState(any());
        verify(metrics, never()).downgradeIgnored(any(), any());
    }

    /**
     * The precedence guard also applies when a PARKED status is drained: a parked in-progress that
     * drains AFTER the alarm has become correlated must NOT downgrade it.
     */
    @Test
    void parkedInProgressDrainedAfterCorrelatedIsIgnored() {
        Instant changedAt = Instant.parse("2026-06-13T13:06:34.426Z");
        when(pending.claim("ALM-CHILD")).thenReturn(Optional.of(new PendingStatus(
                "ALM-CHILD", "in-progress", "correlation-engine", changedAt, "evt-lagging",
                Instant.parse("2026-06-13T13:06:34.500Z"))));
        // By the time the park drains, the alarm is already correlated.
        when(alarms.currentLifecycleState("ALM-CHILD"))
                .thenReturn(Optional.of(LifecycleState.CORRELATED));

        lifecycle.reapplyPending("ALM-CHILD", Instant.now());

        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        verify(transitions).append(eq("ALM-CHILD"), eq("correlated"),
                eq(LifecycleService.REASON_DOWNGRADE_IGNORED), any(), any(), any(), any());
        verify(metrics).downgradeIgnored("correlated", "in-progress");
    }

    @Test
    void revertToOpenClearsProvisionalRoleAndAuditsReason() {
        when(alarms.exists("ALM-0001")).thenReturn(true);

        lifecycle.revertToOpen("ALM-0001", "correlation-engine",
                Instant.parse("2026-06-13T09:10:00Z"), "evt-3", Instant.now());

        verify(alarms).revertToOpenClearingProvisionalRole(eq("ALM-0001"), any());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(transitions).append(eq("ALM-0001"), eq("open"), reason.capture(), any(), any(),
                any(), any());
        assertThat(reason.getValue()).contains("reverted");
    }
}
