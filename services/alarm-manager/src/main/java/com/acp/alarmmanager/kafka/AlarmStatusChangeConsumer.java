package com.acp.alarmmanager.kafka;

import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.service.StatusSyncService;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer for {@code alarms.status.changed} — the canonical STATE channel. Codec-validates the
 * raw bytes (rejecting unknown {@code schemaVersion} and any {@code newStatus} outside the frozen
 * enum), confirms the envelope {@code type} is {@code AlarmStatusChange}, then hands off to
 * {@link StatusSyncService} (idempotent on envelope {@code eventId}). Any codec/type/enum failure
 * routes the raw bytes to {@code alarms.status.changed.dlq}, the store is not modified, the offset
 * is committed, and processing of subsequent messages continues.
 */
@Component
public class AlarmStatusChangeConsumer {

    private static final String EXPECTED_TYPE = "AlarmStatusChange";
    private static final Logger log = LoggerFactory.getLogger(AlarmStatusChangeConsumer.class);

    private final EventCodec codec;
    private final StatusSyncService statusSync;
    private final DlqRouter dlq;
    private final AlarmManagerProperties.Kafka cfg;

    public AlarmStatusChangeConsumer(EventCodec codec, StatusSyncService statusSync, DlqRouter dlq,
            AlarmManagerProperties properties) {
        this.codec = codec;
        this.statusSync = statusSync;
        this.dlq = dlq;
        this.cfg = properties.getKafka();
    }

    @KafkaListener(topics = "${alarm-manager.kafka.status-topic}",
            groupId = "${alarm-manager.kafka.group-id-status}",
            containerFactory = "statusListenerFactory")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String json = record.value() == null ? "" : new String(record.value(), StandardCharsets.UTF_8);
        try {
            TypedEnvelope<Object> envelope = codec.deserialize(json);
            if (!EXPECTED_TYPE.equals(envelope.getType())) {
                throw new IllegalArgumentException(
                        "unexpected envelope type '" + envelope.getType() + "' on "
                                + cfg.getStatusTopic());
            }
            MDC.put("traceId", envelope.getTraceId());
            statusSync.apply(envelope);
        } catch (Exception e) {
            dlq.route(cfg.getStatusDlq(), cfg.getStatusTopic(), record.key(), record.value(), e);
        } finally {
            MDC.clear();
            ack.acknowledge();
        }
    }
}
