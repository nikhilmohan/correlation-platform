package com.acp.correlationengine.integration;

import com.acp.correlationengine.observability.CorrelationMetrics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Routes poison/unparseable messages to {@code <topic>.dlq} with diagnostic headers
 * ({@code x-source-topic}, {@code x-exception}) — never silently dropped (AC19). Processing of the
 * next valid message continues uninterrupted (the consumer acks past the poison record).
 */
public class DlqProducer {

    private static final Logger log = LoggerFactory.getLogger(DlqProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final CorrelationMetrics metrics;

    public DlqProducer(KafkaTemplate<String, String> kafka, CorrelationMetrics metrics) {
        this.kafka = kafka;
        this.metrics = metrics;
    }

    public void route(String sourceTopic, String key, String rawPayload, Throwable error) {
        String dlqTopic = sourceTopic + ".dlq";
        ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, key, rawPayload);
        record.headers().add(new RecordHeader("x-source-topic",
                sourceTopic.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("x-exception",
                String.valueOf(error == null ? "unknown" : error.getMessage())
                        .getBytes(StandardCharsets.UTF_8)));
        kafka.send(record);
        metrics.incrementDlqRouted();
        log.warn("Routed poison message from {} to {}: {}", sourceTopic, dlqTopic,
                error == null ? "" : error.getMessage());
    }
}
