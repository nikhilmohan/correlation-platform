package com.acp.topology.events;

import com.acp.topology.config.TopologyProperties;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Routes an undeliverable {@code topology.changed} envelope to {@code topology.changed.dlq} with
 * diagnostic error headers (EH-8). The original event payload is preserved verbatim so it can be
 * replayed; the failure is recorded on the {@code x-error} header rather than dropped.
 */
@Component
public class DlqPublisher {

    private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TopologyProperties.Kafka config;

    public DlqPublisher(KafkaTemplate<String, String> kafkaTemplate, TopologyProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.config = properties.getKafka();
    }

    /**
     * Publish the (serialized) envelope to the DLQ keyed by {@code snapshotId}, tagging the original
     * topic, trace id and the failure cause on headers. Never throws — a DLQ failure is logged, not
     * propagated (the inbound ingest already returned 200; EH-8).
     */
    public void publish(String wire, String snapshotId, String traceId, Throwable cause) {
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(config.getDlqTopic(), snapshotId, wire);
            record.headers().add("x-error",
                    String.valueOf(cause == null ? "unknown" : cause.getMessage())
                            .getBytes(StandardCharsets.UTF_8));
            record.headers().add("x-original-topic",
                    config.getTopic().getBytes(StandardCharsets.UTF_8));
            record.headers().add("x-trace-id",
                    String.valueOf(traceId).getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
            log.warn("routed topology.changed to DLQ snapshotId={} traceId={}", snapshotId, traceId);
        } catch (Exception dlqError) {
            log.error("failed to route topology.changed to DLQ snapshotId={}", snapshotId, dlqError);
        }
    }
}
