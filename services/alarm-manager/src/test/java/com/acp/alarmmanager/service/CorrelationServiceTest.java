package com.acp.alarmmanager.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.domain.Role;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import com.acp.eventmodel.TypedEnvelope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AC4 — root-cause/child roles + incidentId assigned, one role-assigned audit per alarm, STATE
 * untouched. AC5 — same eventId twice applied exactly once (no duplicate audit).
 */
class CorrelationServiceTest {

    private ProcessedEventRepository processed;
    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private AmMetrics metrics;
    private CorrelationService correlation;

    @BeforeEach
    void setUp() {
        processed = Mockito.mock(ProcessedEventRepository.class);
        alarms = Mockito.mock(AlarmRepository.class);
        transitions = Mockito.mock(StateTransitionRepository.class);
        metrics = Mockito.mock(AmMetrics.class);
        correlation = new CorrelationService(processed, alarms, transitions, metrics);
    }

    @Test
    void appliesRoleAndIncidentOnlyWithAuditLeavingStateUntouched() {
        when(processed.claim(any(), any())).thenReturn(true);
        when(alarms.exists(any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.correlationEnvelope("evt-corr", "INC-0001",
                "ALM-0001", List.of("ALM-0002", "ALM-0003"));

        correlation.applyRoleAndIncident(env);

        // Root-cause gets root-cause + incidentId.
        verify(alarms).updateRoleAndIncident(eq("ALM-0001"), eq(Role.ROOT_CAUSE), eq("INC-0001"),
                any());
        // Each child gets child + same incidentId.
        verify(alarms).updateRoleAndIncident(eq("ALM-0002"), eq(Role.CHILD), eq("INC-0001"), any());
        verify(alarms).updateRoleAndIncident(eq("ALM-0003"), eq(Role.CHILD), eq("INC-0001"), any());
        // One role-assigned audit per affected alarm (3 total). STATE (lifecycle) never touched:
        // CorrelationService never calls updateLifecycleState (no such interaction to verify).
        verify(transitions, times(3)).append(any(), eq("role-assigned"), any(), any(), any(), any(),
                any());
        verify(metrics).correlationApplied();
    }

    @Test
    void redeliveredEventAppliedExactlyOnce() {
        when(processed.claim(eq("evt-corr"), any())).thenReturn(true).thenReturn(false);
        when(alarms.exists(any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.correlationEnvelope("evt-corr", "INC-0001",
                "ALM-0001", List.of("ALM-0002"));

        correlation.applyRoleAndIncident(env);
        correlation.applyRoleAndIncident(env);

        // Applied once: 2 alarms updated once each, not twice.
        verify(alarms, times(1)).updateRoleAndIncident(eq("ALM-0001"), any(), any(), any());
        verify(alarms, times(1)).updateRoleAndIncident(eq("ALM-0002"), any(), any(), any());
        verify(transitions, times(2)).append(any(), eq("role-assigned"), any(), any(), any(),
                any(), any());
    }

    @Test
    void correlationForUnknownAlarmIsNoOpWithMetric() {
        when(processed.claim(any(), any())).thenReturn(true);
        when(alarms.exists("ALM-0001")).thenReturn(false);
        when(alarms.exists("ALM-0002")).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.correlationEnvelope("evt-corr", "INC-0001",
                "ALM-0001", List.of("ALM-0002"));

        correlation.applyRoleAndIncident(env);

        verify(alarms, never()).updateRoleAndIncident(eq("ALM-0001"), any(), any(), any());
        verify(alarms).updateRoleAndIncident(eq("ALM-0002"), eq(Role.CHILD), any(), any());
        verify(metrics).correlationForUnknownAlarm();
    }
}
