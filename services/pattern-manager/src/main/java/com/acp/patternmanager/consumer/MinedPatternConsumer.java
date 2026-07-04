package com.acp.patternmanager.consumer;

import com.acp.eventmodel.CodecException;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.patternmanager.config.KafkaTopicProperties;
import com.acp.patternmanager.enrichment.PatternEnrichmentService;
import com.acp.patternmanager.enrichment.PatternEnrichmentService.MinedPatternView;
import com.acp.patternmanager.store.PatternStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code patterns.mined}. For each record:
 * <ol>
 *   <li>deserialize + validate via {@link EventCodec} (envelope + payload + schemaVersion policy);
 *   <li>on parse/validation/schemaVersion failure route the raw bytes to {@code patterns.mined.dlq}
 *       and ACK (poison — never restarts, never silently drops — criterion 11);
 *   <li>idempotency gate on {@code eventId} ({@code processed_event}); a duplicate skips + acks;
 *   <li>otherwise enrich + persist + emit, then ACK (manual, at-least-once).
 * </ol>
 *
 * <p>A well-formed event whose collaborator call fails transiently is NOT DLQ'd — the exception
 * propagates so the offset stays uncommitted and the message is redelivered after recovery.
 */
@Component
@ConditionalOnProperty(name = "pattern-manager.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MinedPatternConsumer {

    private static final Logger log = LoggerFactory.getLogger(MinedPatternConsumer.class);

    private final EventCodec codec;
    private final ObjectMapper objectMapper;
    private final PatternEnrichmentService enrichmentService;
    private final PatternStoreService patternStoreService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topics;

    public MinedPatternConsumer(EventCodec codec, ObjectMapper objectMapper,
            PatternEnrichmentService enrichmentService, PatternStoreService patternStoreService,
            KafkaTemplate<String, String> patternKafkaTemplate, KafkaTopicProperties topics) {
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.enrichmentService = enrichmentService;
        this.patternStoreService = patternStoreService;
        this.kafkaTemplate = patternKafkaTemplate;
        this.topics = topics;
    }

    @KafkaListener(
            topics = "${pattern-manager.kafka.topics.mined:patterns.mined}",
            groupId = "${pattern-manager.kafka.consumer-group-id:pattern-manager-patterns.mined}",
            containerFactory = "patternKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String raw = record.value();
        TypedEnvelope<Object> envelope;
        try {
            envelope = codec.deserialize(raw);
        } catch (CodecException e) {
            // CodecException is the base of SchemaVersionException + UnknownEventTypeException, so a
            // single catch covers all poison cases (malformed JSON, missing field, unknown type,
            // unsupported schemaVersion) -> DLQ + ack, never restart, never silently drop.
            routeToDlq(record, e);
            ack.acknowledge();
            return;
        }

        if (!"PatternMinedEvent".equals(envelope.getType())) {
            routeToDlq(record, new IllegalArgumentException(
                    "unexpected event type on patterns.mined: " + envelope.getType()));
            ack.acknowledge();
            return;
        }

        String eventId = envelope.getEventId();
        String source = envelope.getSource();

        // Idempotency gate: a redelivered eventId is a no-op (criterion 10).
        if (patternStoreService.alreadyProcessed(eventId)) {
            log.info("duplicate eventId {} skipped (already processed)", eventId);
            ack.acknowledge();
            return;
        }

        // Re-parse the payload node for enrichment (the codec bound it to the POJO; we work off the
        // JSON node so the open `timing`/`provenance` maps are available verbatim).
        JsonNode payload = payloadNode(raw);
        MinedPatternView view = MinedPatternView.from(payload, objectMapper);

        // A transient collaborator failure here PROPAGATES — offset stays uncommitted, redelivered.
        enrichmentService.enrichAndPersist(view, eventId, source, envelope.getTraceId());
        ack.acknowledge();
    }

    private JsonNode payloadNode(String raw) {
        try {
            return objectMapper.readTree(raw).path("payload");
        } catch (Exception e) {
            // Unreachable: the codec already parsed this JSON successfully.
            throw new IllegalStateException("failed to re-parse validated payload", e);
        }
    }

    private void routeToDlq(ConsumerRecord<String, String> record, Exception cause) {
        log.error("routing poison patterns.mined message to DLQ: {}", cause.getMessage());
        var producerRecord = new org.apache.kafka.clients.producer.ProducerRecord<String, String>(
                topics.minedDlq(), record.key(), record.value());
        producerRecord.headers().add("error",
                String.valueOf(cause.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        producerRecord.headers().add("errorClass",
                cause.getClass().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(producerRecord);
    }
}
