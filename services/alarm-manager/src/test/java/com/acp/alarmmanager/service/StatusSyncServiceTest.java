package com.acp.alarmmanager.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AC16 — in-progress sets state + audits source/changedAt. AC17 — reverted-open returns to open
 * clearing provisional role. Plus envelope-eventId idempotency (STATE channel).
 */
class StatusSyncServiceTest {

    private ProcessedEventRepository processed;
    private LifecycleService lifecycle;
    private AmMetrics metrics;
    private StatusSyncService statusSync;

    @BeforeEach
    void setUp() {
        processed = Mockito.mock(ProcessedEventRepository.class);
        lifecycle = Mockito.mock(LifecycleService.class);
        metrics = Mockito.mock(AmMetrics.class);
        statusSync = new StatusSyncService(processed, lifecycle, metrics);
    }

    @Test
    void inProgressSetsStateAndAuditsSourceAndChangedAt() {
        when(processed.claim(any(), any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-ip", "ALM-0001", "in-progress");

        statusSync.apply(env);

        verify(lifecycle).applyState(eq("ALM-0001"), eq(LifecycleState.IN_PROGRESS),
                eq("correlation-engine"), eq(Instant.parse("2026-06-13T09:05:00Z")), eq("evt-ip"),
                any());
        verify(metrics).statusApplied("in-progress");
    }

    @Test
    void revertedOpenReturnsToOpenWithReasonAndClearsProvisionalRole() {
        when(processed.claim(any(), any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-rev", "ALM-0001", "reverted-open");

        statusSync.apply(env);

        verify(lifecycle).revertToOpen(eq("ALM-0001"), eq("correlation-engine"), any(),
                eq("evt-rev"), any());
    }

    @Test
    void correlatedSetsStateButDoesNotTouchRole() {
        when(processed.claim(any(), any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-c", "ALM-0001", "correlated");

        statusSync.apply(env);

        verify(lifecycle).applyState(eq("ALM-0001"), eq(LifecycleState.CORRELATED), any(), any(),
                eq("evt-c"), any());
    }

    @Test
    void clearedRoutesThroughLifecycleClear() {
        when(processed.claim(any(), any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-cl", "ALM-0001", "cleared");

        statusSync.apply(env);

        verify(lifecycle).clear(eq("ALM-0001"), any(), any(), eq("evt-cl"), any());
    }

    @Test
    void redeliveredStatusEventAppliedExactlyOnce() {
        when(processed.claim(eq("evt-ip"), any())).thenReturn(true).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-ip", "ALM-0001", "in-progress");

        statusSync.apply(env);
        statusSync.apply(env);

        verify(lifecycle, times(1)).applyState(any(), any(), any(), any(), any(), any());
    }

    @Test
    void secondDeliveryIsNoOpAndAppliesNothing() {
        when(processed.claim(any(), any())).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.statusEnvelope("evt-ip", "ALM-0001", "in-progress");

        statusSync.apply(env);

        verify(lifecycle, never()).applyState(any(), any(), any(), any(), any(), any());
    }
}
