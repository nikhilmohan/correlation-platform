package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The atomic persist boundary (extracted from IngestService so the {@code @Transactional} proxy
 * actually intercepts — M1). AC1 — persist open with all fields (incl. canonical alarmType) plus a
 * single open audit entry. AC3 (persist half) — same alarmId twice: one persist, one open audit.
 */
class AlarmPersisterTest {

    private AlarmRepository alarms;
    private LifecycleService lifecycle;
    private AmMetrics metrics;
    private AlarmPersister persister;

    @BeforeEach
    void setUp() {
        alarms = Mockito.mock(AlarmRepository.class);
        lifecycle = Mockito.mock(LifecycleService.class);
        metrics = Mockito.mock(AmMetrics.class);
        AlarmMapper mapper = new AlarmMapper(new EventCodec());
        persister = new AlarmPersister(alarms, mapper, lifecycle, metrics);
    }

    @Test
    void persistsAlarmOpenWithAllFieldsIncludingAlarmTypeAndSingleOpenTransition() {
        when(alarms.insertIfAbsent(any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        persister.persistOpen(env);

        ArgumentCaptor<AlarmRecord> captor = ArgumentCaptor.forClass(AlarmRecord.class);
        verify(alarms).insertIfAbsent(captor.capture());
        AlarmRecord persisted = captor.getValue();
        assertThat(persisted.alarmId()).isEqualTo("ALM-0001");
        assertThat(persisted.lifecycleState()).isEqualTo(LifecycleState.OPEN);
        assertThat(persisted.managedObjectId()).isEqualTo("Port:ne1-1-1");
        assertThat(persisted.perceivedSeverity()).isEqualTo("critical");
        assertThat(persisted.raisedAt()).isEqualTo(Instant.parse("2026-06-13T09:00:00Z"));
        assertThat(persisted.trailIds()).containsExactly("trail-77");
        // Canonical alarmType join token stored distinct from eventType/probableCause.
        assertThat(persisted.alarmType()).isEqualTo("PortDown");
        assertThat(persisted.eventType()).isEqualTo("communicationsAlarm");
        assertThat(persisted.probableCause()).isEqualTo("lossOfSignal");

        // Exactly one ingest open audit entry (committed atomically with the insert).
        verify(lifecycle, times(1)).recordIngestOpen(eq("ALM-0001"), anyString(), any());
        // Ordering-race fix: after persisting, re-apply any parked status (e.g. a correlated that
        // raced ahead of this alarm) in the same transaction.
        verify(lifecycle, times(1)).reapplyPending(eq("ALM-0001"), any());
    }

    @Test
    void redeliveryProducesNoDoublePersistNoDoubleOpenAudit() {
        // First delivery inserts; second finds the row present (insertIfAbsent -> false).
        when(alarms.insertIfAbsent(any())).thenReturn(true).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        persister.persistOpen(env);
        persister.persistOpen(env);

        // Two insert attempts (both idempotent), but only ONE open audit entry and ONE re-apply
        // (only the first delivery actually inserts, so the parked-status re-apply runs once).
        verify(alarms, times(2)).insertIfAbsent(any());
        verify(lifecycle, times(1)).recordIngestOpen(eq("ALM-0001"), anyString(), any());
        verify(lifecycle, times(1)).reapplyPending(eq("ALM-0001"), any());
    }
}
