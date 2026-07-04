package com.acp.alarmmanager.kafka;

import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.service.IngestService;
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
 * Consumer for {@code alarms.enriched.live}. Codec-validates the raw bytes (rejecting unknown
 * {@code schemaVersion}, malformed {@code managedObjectId}, missing required fields), confirms the
 * envelope {@code type} is {@code AlarmEvent}, then hands off to {@link IngestService}. Any
 * codec/type failure routes the raw bytes to {@code alarms.enriched.live.dlq} and the offset is
 * committed so processing continues; nothing is dropped silently. Dedupe on {@code alarmId} is
 * done downstream (PK upsert + {@code published} guard).
 */
@Component
public class EnrichedAlarmConsumer {

    private static final String EXPECTED_TYPE = "AlarmEvent";
    private static final Logger log = LoggerFactory.getLogger(EnrichedAlarmConsumer.class);

    private final EventCodec codec;
    private final IngestService ingest;
    private final DlqRouter dlq;
    private final AlarmManagerProperties.Kafka cfg;

    public EnrichedAlarmConsumer(EventCodec codec, IngestService ingest, DlqRouter dlq,
            AlarmManagerProperties properties) {
        this.codec = codec;
        this.ingest = ingest;
        this.dlq = dlq;
        this.cfg = properties.getKafka();
    }

    @KafkaListener(topics = "${alarm-manager.kafka.enriched-topic}",
            groupId = "${alarm-manager.kafka.group-id-enriched}",
            containerFactory = "enrichedListenerFactory")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String json = record.value() == null ? "" : new String(record.value(), StandardCharsets.UTF_8);
        try {
            TypedEnvelope<Object> envelope = codec.deserialize(json);
            if (!EXPECTED_TYPE.equals(envelope.getType())) {
                throw new IllegalArgumentException(
                        "unexpected envelope type '" + envelope.getType() + "' on "
                                + cfg.getEnrichedTopic());
            }
            MDC.put("traceId", envelope.getTraceId());
            ingest.handle(envelope);
        } catch (Exception e) {
            dlq.route(cfg.getEnrichedDlq(), cfg.getEnrichedTopic(), record.key(), record.value(), e);
        } finally {
            MDC.clear();
            ack.acknowledge();
        }
    }
}
