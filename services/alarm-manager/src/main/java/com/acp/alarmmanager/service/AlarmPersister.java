package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic persist boundary for the ingest path. Extracted into its own bean (rather than a
 * self-invoked {@code @Transactional} method on {@link IngestService}) so the Spring transaction
 * proxy actually applies: the idempotent alarm upsert and the single ingest-{@code open} audit
 * entry commit together (or roll back together) in one DB transaction. A crash between the two can
 * therefore never leave an alarm row without its ingest-open audit. The republish (Kafka send) is
 * deliberately kept OUTSIDE this transaction — it runs after the commit — so a redelivery cannot
 * double-persist or double-emit.
 */
@Component
public class AlarmPersister {

    private static final Logger log = LoggerFactory.getLogger(AlarmPersister.class);

    private final AlarmRepository alarms;
    private final AlarmMapper mapper;
    private final LifecycleService lifecycle;
    private final AmMetrics metrics;

    public AlarmPersister(AlarmRepository alarms, AlarmMapper mapper, LifecycleService lifecycle,
            AmMetrics metrics) {
        this.alarms = alarms;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
    }

    /**
     * Idempotently persist the alarm {@code open} and, on first insert only, append the single
     * {@code open} ingest audit entry — both in ONE transaction (real proxy interception, not a
     * self-invocation).
     */
    @Transactional
    public void persistOpen(TypedEnvelope<Object> envelope) {
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
}
