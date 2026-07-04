package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final AlarmRepository alarms;
    private final AlarmMapper mapper;
    private final LifecycleService lifecycle;
    private final PersistedAlarmProducer producer;
    private final AmMetrics metrics;

    public IngestService(AlarmRepository alarms, AlarmMapper mapper, LifecycleService lifecycle,
            PersistedAlarmProducer producer, AmMetrics metrics) {
        this.alarms = alarms;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.producer = producer;
        this.metrics = metrics;
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
     * first insert, then republish. The persist + ingest-audit run in one DB transaction; the
     * republish (send) happens after that commit so a redelivery cannot double-persist or
     * double-emit.
     */
    public void persistAndRepublish(TypedEnvelope<Object> envelope) {
        AlarmEvent payload = (AlarmEvent) envelope.getPayload();
        persistOpen(envelope);
        producer.republish(payload.getAlarmId(), envelope);
    }

    @Transactional
    protected void persistOpen(TypedEnvelope<Object> envelope) {
        Instant now = Instant.now();
        AlarmRecord record = mapper.toOpenRecord(envelope, now);
        boolean inserted = alarms.insertIfAbsent(record);
        if (inserted) {
            lifecycle.recordIngestOpen(record.alarmId(), envelope.getEventId(), now);
            metrics.persisted();
            log.info("persisted alarmId={} lifecycle=open alarmType={}", record.alarmId(),
                    record.alarmType());
        } else {
            log.debug("alarmId={} already persisted — skip persist (redelivery)", record.alarmId());
        }
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
