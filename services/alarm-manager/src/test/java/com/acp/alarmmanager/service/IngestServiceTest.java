package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * AC1 — persist open with all fields (incl. canonical alarmType) + single open audit entry.
 * AC3 — same alarmId twice: one persist, one open audit, one republish (idempotent).
 */
class IngestServiceTest {

    private AlarmRepository alarms;
    private LifecycleService lifecycle;
    private PersistedAlarmProducer producer;
    private AmMetrics metrics;
    private IngestService ingest;

    @BeforeEach
    void setUp() {
        alarms = Mockito.mock(AlarmRepository.class);
        lifecycle = Mockito.mock(LifecycleService.class);
        producer = Mockito.mock(PersistedAlarmProducer.class);
        metrics = Mockito.mock(AmMetrics.class);
        AlarmMapper mapper = new AlarmMapper(new EventCodec());
        ingest = new IngestService(alarms, mapper, lifecycle, producer, metrics);
    }

    @Test
    void persistsAlarmOpenWithAllFieldsIncludingAlarmTypeAndSingleOpenTransition() {
        when(alarms.insertIfAbsent(any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        ingest.handle(env);

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

        // Exactly one ingest open audit entry, and a republish.
        verify(lifecycle, times(1)).recordIngestOpen(eq("ALM-0001"), anyString(), any());
        verify(producer, times(1)).republish(eq("ALM-0001"), any());
    }

    @Test
    void redeliveryProducesNoDoublePersistNoDoubleRepublish() {
        // First delivery inserts; second finds the row present (insertIfAbsent -> false).
        when(alarms.insertIfAbsent(any())).thenReturn(true).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        ingest.handle(env);
        ingest.handle(env);

        // Two insert attempts (both idempotent), but only ONE open audit entry.
        verify(alarms, times(2)).insertIfAbsent(any());
        verify(lifecycle, times(1)).recordIngestOpen(eq("ALM-0001"), anyString(), any());
        // republish() is called each delivery, but the producer's published-guard makes it a
        // single emit — verified in PersistedAlarmProducerTest. Here it is delegated both times.
        verify(producer, times(2)).republish(eq("ALM-0001"), any());
    }

    @Test
    void clearedWireStateTransitionsAlarmToClearedNotPersist() {
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "cleared");

        ingest.handle(env);

        verify(alarms, never()).insertIfAbsent(any());
        verify(lifecycle, times(1)).clear(eq("ALM-0001"), anyString(), any(), anyString(), any());
    }
}
