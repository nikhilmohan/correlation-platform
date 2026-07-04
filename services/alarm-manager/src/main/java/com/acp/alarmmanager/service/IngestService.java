package com.acp.alarmmanager.service;

import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Ingest path for {@code alarms.enriched.live}. Persist-first makes the Alarm Manager the SoT:
 * each enriched {@code AlarmEvent} is upserted into the live alarm store with lifecycle
 * {@code open} (idempotent on {@code alarmId}) with a single {@code open} audit entry on first
 * insert, then republished on {@code alarms.persisted.live} (idempotent — no second emit on
 * redelivery). A wire-{@code cleared} {@code AlarmEvent} transitions the existing alarm to
 * {@code cleared} (the canonical clear path is {@code AlarmStatusChange}; both converge on
 * {@link LifecycleService#clear}).
 */
@Service
public class IngestService {

    private final LifecycleService lifecycle;
    private final AlarmPersister persister;
    private final PersistedAlarmProducer producer;

    public IngestService(LifecycleService lifecycle, AlarmPersister persister,
            PersistedAlarmProducer producer) {
        this.lifecycle = lifecycle;
        this.persister = persister;
        this.producer = producer;
    }

    /** Handle one codec-validated {@code AlarmEvent} envelope (branch on the wire {@code state}). */
    public void handle(TypedEnvelope<Object> envelope) {
        AlarmEvent payload = (AlarmEvent) envelope.getPayload();
        AlarmEvent.State state = payload.getState();
        if (state == AlarmEvent.State.CLEARED) {
            lifecycle.clear(payload.getAlarmId(), envelope.getSource(),
                    parseInstant(payload.getClearedAt()), envelope.getEventId(), Instant.now());
        } else {
            persistAndRepublish(envelope);
        }
    }

    /**
     * Persist the alarm {@code open} (idempotent), write the single {@code open} audit entry on
     * first insert, then republish. The persist + ingest-audit run atomically in one DB transaction
     * inside {@link AlarmPersister#persistOpen} (a distinct bean, so the {@code @Transactional}
     * proxy actually applies — no self-invocation); the republish (send) happens after that commit
     * so a redelivery cannot double-persist or double-emit.
     */
    public void persistAndRepublish(TypedEnvelope<Object> envelope) {
        AlarmEvent payload = (AlarmEvent) envelope.getPayload();
        persister.persistOpen(envelope);
        producer.republish(payload.getAlarmId(), envelope);
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
