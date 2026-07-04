package com.acp.enrichment.kafka;

import com.acp.enrichment.config.EnrichmentProperties;
import com.acp.enrichment.pipeline.Path;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Routes offending raw bytes (plus failure-metadata headers) to the matching {@code <topic>.dlq}
 * and lets processing continue (design "DlqRouter", Error handling). Nothing is ever silently
 * dropped — every DLQ send increments {@code dlq_messages_total{topic,reason}} and logs.
 */
@Component
public class DlqRouter {

    private static final Logger log = LoggerFactory.getLogger(DlqRouter.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EnrichmentProperties props;
    private final MeterRegistry meters;

    public DlqRouter(KafkaTemplate<String, String> kafkaTemplate, EnrichmentProperties props,
            MeterRegistry meters) {
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.meters = meters;
    }

    /**
     * @param path the originating path (selects the DLQ topic)
     * @param rawValue the original message bytes/string
     * @param reason the failure reason label (e.g. {@code deserialize}, {@code schema_version},
     *     {@code normalize_invalid}, {@code alarmtype_unmapped}, {@code trail_lookup})
     * @param detail human-readable failure detail (header only)
     */
    public void route(Path path, String rawValue, String reason, String detail) {
        String dlqTopic = path == Path.LIVE ? props.getLiveDlqTopic() : props.getHistoryDlqTopic();
        RecordHeaders headers = new RecordHeaders();
        headers.add("dlq-reason", bytes(reason));
        headers.add("dlq-detail", bytes(detail == null ? "" : detail));
        headers.add("dlq-origin-topic",
                bytes(path == Path.LIVE ? props.getLiveTopic() : props.getHistoryTopic()));
        ProducerRecord<String, String> record =
                new ProducerRecord<>(dlqTopic, null, null, null, rawValue, headers);
        kafkaTemplate.send(record);
        meters.counter("dlq_messages_total", "topic", dlqTopic, "reason", reason).increment();
        log.error("routed message to {} reason={} detail={}", dlqTopic, reason, detail);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
