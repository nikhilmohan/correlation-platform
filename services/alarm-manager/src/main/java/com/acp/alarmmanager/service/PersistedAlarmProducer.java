package com.acp.alarmmanager.service;

import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Republishes each persisted alarm on {@code alarms.persisted.live} so the Correlation Engine
 * consumes persisted (SoT) alarms rather than raw enriched ones. Idempotent: it flips the alarm
 * row's {@code published} flag false to true atomically and emits only when it won that flip, so a
 * Kafka redelivery never produces a second emit. The republished value is the SAME
 * {@code AlarmEvent} — a faithful re-serialize of the consumed envelope via the frozen codec.
 */
@Component
public class PersistedAlarmProducer {

    private static final Logger log = LoggerFactory.getLogger(PersistedAlarmProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AlarmRepository alarms;
    private final EventCodec codec;
    private final AmMetrics metrics;
    private final String topic;

    public PersistedAlarmProducer(KafkaTemplate<String, String> kafkaTemplate, AlarmRepository alarms,
            EventCodec codec, AmMetrics metrics, AlarmManagerProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.alarms = alarms;
        this.codec = codec;
        this.metrics = metrics;
        this.topic = properties.getKafka().getPersistedTopic();
    }

    /**
     * Republish the same {@code AlarmEvent} envelope for {@code alarmId} exactly once. The
     * {@code envelope} is the faithful consumed envelope (re-serialized to canonical wire form).
     */
    public void republish(String alarmId, TypedEnvelope<Object> envelope) {
        if (!alarms.markPublished(alarmId, Instant.now())) {
            log.debug("alarmId={} already republished — skip (redelivery)", alarmId);
            return;
        }
        String wire = codec.serialize(envelope);
        try {
            // Block on the broker ack: the published guard was claimed above (so a concurrent
            // redelivery cannot also emit), but we only KEEP the flag flipped once the send
            // actually succeeds. On failure we roll the guard back so a Kafka redelivery
            // re-attempts the emit — closing the lost-emit window while staying single-emit.
            kafkaTemplate.send(topic, alarmId, wire).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            alarms.unmarkPublished(alarmId, Instant.now());
            throw new IllegalStateException("interrupted republishing alarmId=" + alarmId, ex);
        } catch (RuntimeException | java.util.concurrent.ExecutionException ex) {
            alarms.unmarkPublished(alarmId, Instant.now());
            log.warn("republish send failed for alarmId={} — published guard rolled back for retry",
                    alarmId, ex);
            throw new IllegalStateException("failed to republish alarmId=" + alarmId, ex);
        }
        metrics.republished();
        log.info("republished alarmId={} on {}", alarmId, topic);
    }
}
