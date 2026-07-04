package com.acp.alarmmanager.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.acp.alarmmanager.Fixtures;
import com.acp.eventmodel.TypedEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AC1/AC4 — the ingest branch: a wire-{@code raised} AlarmEvent delegates to the atomic persist
 * boundary ({@link AlarmPersister}) then republishes; a wire-{@code cleared} AlarmEvent transitions
 * to cleared instead of persisting. Field-level persist + single-open-audit assertions live in
 * {@link AlarmPersisterTest}. AC3 — same alarmId twice delegates persist each time (persist +
 * republish idempotency is proven in {@link AlarmPersisterTest} / {@link PersistedAlarmProducerTest}).
 */
class IngestServiceTest {

    private LifecycleService lifecycle;
    private AlarmPersister persister;
    private PersistedAlarmProducer producer;
    private IngestService ingest;

    @BeforeEach
    void setUp() {
        lifecycle = Mockito.mock(LifecycleService.class);
        persister = Mockito.mock(AlarmPersister.class);
        producer = Mockito.mock(PersistedAlarmProducer.class);
        ingest = new IngestService(lifecycle, persister, producer);
    }

    @Test
    void raisedDelegatesToAtomicPersistThenRepublishes() {
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        ingest.handle(env);

        // Persist + single-open-audit is one transactional unit inside AlarmPersister.
        verify(persister, times(1)).persistOpen(env);
        verify(producer, times(1)).republish(eq("ALM-0001"), any());
        verify(lifecycle, never()).clear(anyString(), any(), any(), any(), any());
    }

    @Test
    void redeliveryStillDelegatesPersistAndRepublishEachTime() {
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        ingest.handle(env);
        ingest.handle(env);

        // Delegated both times; the persist-once / republish-once guards live in the persister
        // (insertIfAbsent) and the producer (published flag) respectively.
        verify(persister, times(2)).persistOpen(env);
        verify(producer, times(2)).republish(eq("ALM-0001"), any());
    }

    @Test
    void clearedWireStateTransitionsAlarmToClearedNotPersist() {
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "cleared");

        ingest.handle(env);

        verify(persister, never()).persistOpen(any());
        verify(producer, never()).republish(anyString(), any());
        verify(lifecycle, times(1)).clear(eq("ALM-0001"), anyString(), any(), anyString(), any());
    }
}
