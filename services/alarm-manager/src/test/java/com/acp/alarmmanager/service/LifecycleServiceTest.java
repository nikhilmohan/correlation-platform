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
        when(alarms.exists("ALM-0001")).thenReturn(true);
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
        // Alarm has just been persisted, so the re-apply takes the apply branch.
        when(alarms.exists("ALM-RACE")).thenReturn(true);

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
        // First exists()=false (park branch), then true (persisted during the park window).
        when(alarms.exists("ALM-RACE")).thenReturn(false, true);
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
        when(alarms.exists("ALM-RACE")).thenReturn(false, false);

        lifecycle.applyState("ALM-RACE", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        verify(pending).upsert(any());
        verify(pending, never()).claim(any());
        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void appliesInProgressStateWithSourceAndChangedAtOnAudit() {
        when(alarms.exists("ALM-0001")).thenReturn(true);
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");

        lifecycle.applyState("ALM-0001", LifecycleState.IN_PROGRESS, "correlation-engine",
                changedAt, "evt-2", Instant.now());

        verify(alarms).updateLifecycleState(eq("ALM-0001"), eq(LifecycleState.IN_PROGRESS),
                isNull(), any());
        verify(transitions).append(eq("ALM-0001"), eq("in-progress"), any(),
                eq("correlation-engine"), eq(changedAt), eq("evt-2"), any());
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
