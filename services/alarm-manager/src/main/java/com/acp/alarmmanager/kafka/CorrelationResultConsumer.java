package com.acp.alarmmanager.kafka;

import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.service.CorrelationService;
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
 * Consumer for {@code correlation.results} — the canonical ROLE + incident channel. Codec-validates
 * the raw bytes, confirms the envelope {@code type} is {@code CorrelationResultEvent}, then hands
 * off to {@link CorrelationService} (idempotent on envelope {@code eventId}, ROLE + incident only).
 * Any codec/type failure routes the raw bytes to {@code correlation.results.dlq} and the offset is
 * committed so processing continues.
 */
@Component
public class CorrelationResultConsumer {

    private static final String EXPECTED_TYPE = "CorrelationResultEvent";
    private static final Logger log = LoggerFactory.getLogger(CorrelationResultConsumer.class);

    private final EventCodec codec;
    private final CorrelationService correlation;
    private final DlqRouter dlq;
    private final AlarmManagerProperties.Kafka cfg;

    public CorrelationResultConsumer(EventCodec codec, CorrelationService correlation, DlqRouter dlq,
            AlarmManagerProperties properties) {
        this.codec = codec;
        this.correlation = correlation;
        this.dlq = dlq;
        this.cfg = properties.getKafka();
    }

    @KafkaListener(topics = "${alarm-manager.kafka.correlation-topic}",
            groupId = "${alarm-manager.kafka.group-id-correlation}",
            containerFactory = "correlationListenerFactory")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String json = record.value() == null ? "" : new String(record.value(), StandardCharsets.UTF_8);
        try {
            TypedEnvelope<Object> envelope = codec.deserialize(json);
            if (!EXPECTED_TYPE.equals(envelope.getType())) {
                throw new IllegalArgumentException(
                        "unexpected envelope type '" + envelope.getType() + "' on "
                                + cfg.getCorrelationTopic());
            }
            MDC.put("traceId", envelope.getTraceId());
            correlation.applyRoleAndIncident(envelope);
        } catch (Exception e) {
            dlq.route(cfg.getCorrelationDlq(), cfg.getCorrelationTopic(), record.key(),
                    record.value(), e);
        } finally {
            MDC.clear();
            ack.acknowledge();
        }
    }
}
