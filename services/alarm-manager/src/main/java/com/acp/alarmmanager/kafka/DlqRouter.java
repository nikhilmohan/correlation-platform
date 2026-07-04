package com.acp.alarmmanager.kafka;

import com.acp.alarmmanager.service.AmMetrics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Routes an unprocessable message (schema-invalid, unknown {@code schemaVersion}, unrecognised
 * enum value, wrong envelope {@code type}, or exhausted retries) to the matching {@code
 * <topic>.dlq}, preserving the original raw bytes plus failure-metadata headers. Nothing is ever
 * dropped silently. Never throws — a DLQ failure is logged, not propagated.
 */
@Component
public class DlqRouter {

    private static final Logger log = LoggerFactory.getLogger(DlqRouter.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AmMetrics metrics;

    public DlqRouter(KafkaTemplate<String, String> kafkaTemplate, AmMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    /**
     * Send the raw record (as a UTF-8 string) to {@code dlqTopic} with diagnostic headers.
     *
     * @param dlqTopic    the target {@code <topic>.dlq}
     * @param sourceTopic the originating topic
     * @param key         the record key (may be null)
     * @param rawValue    the original raw bytes
     * @param cause       the failure cause
     */
    public void route(String dlqTopic, String sourceTopic, String key, byte[] rawValue,
            Throwable cause) {
        try {
            String value = rawValue == null ? "" : new String(rawValue, StandardCharsets.UTF_8);
            ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, key, value);
            record.headers().add("x-dlq-reason",
                    String.valueOf(cause == null ? "unknown" : cause.getMessage())
                            .getBytes(StandardCharsets.UTF_8));
            record.headers().add("x-dlq-source-topic", sourceTopic.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
            metrics.dlqRouted(dlqTopic);
            log.warn("routed message to DLQ topic={} sourceTopic={} reason={}", dlqTopic,
                    sourceTopic, cause == null ? "unknown" : cause.getMessage());
        } catch (Exception dlqError) {
            log.error("failed to route message to DLQ topic={} sourceTopic={}", dlqTopic,
                    sourceTopic, dlqError);
        }
    }
}
