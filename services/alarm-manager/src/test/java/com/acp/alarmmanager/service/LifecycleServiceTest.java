package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** AC7 — wire-cleared / status-cleared transitions the alarm to cleared with a cleared audit. */
class LifecycleServiceTest {

    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private AmMetrics metrics;
    private LifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        alarms = Mockito.mock(AlarmRepository.class);
        transitions = Mockito.mock(StateTransitionRepository.class);
        metrics = Mockito.mock(AmMetrics.class);
        lifecycle = new LifecycleService(alarms, transitions, metrics);
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
    void clearForUnknownAlarmIsNoOpWithMetric() {
        when(alarms.exists("UNKNOWN")).thenReturn(false);

        lifecycle.clear("UNKNOWN", "simulator", null, "evt-1", Instant.now());

        verify(alarms, never()).updateLifecycleState(any(), any(), any(), any());
        verify(metrics).clearForUnknownAlarm();
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
