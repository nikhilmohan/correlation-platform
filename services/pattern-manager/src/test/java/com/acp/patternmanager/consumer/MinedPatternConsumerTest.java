package com.acp.patternmanager.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acp.eventmodel.EventCodec;
import com.acp.patternmanager.config.KafkaTopicProperties;
import com.acp.patternmanager.enrichment.PatternEnrichmentService;
import com.acp.patternmanager.enrichment.PatternEnrichmentService.MinedPatternView;
import com.acp.patternmanager.store.PatternStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Unit tests for {@link MinedPatternConsumer} — the {@code patterns.mined} listener.
 *
 * <p>Uses a REAL {@link EventCodec} (so envelope/payload/schemaVersion validation behaves exactly
 * as in production) with mocked collaborators (enrichment, store, KafkaTemplate). Real Kafka is a
 * separate Testcontainers IT (tracked follow-up); these cover the AC-10 idempotency and AC-11 DLQ
 * decision logic + the happy path deterministically without a broker.
 */
@ExtendWith(MockitoExtension.class)
class MinedPatternConsumerTest {

    // A canonical, contract-valid PatternMinedEvent envelope (mirrors the event-model fixture).
    private static final String VALID_MINED = """
            {
              "eventId": "66666666-6666-4666-8666-666666666666",
              "type": "PatternMinedEvent",
              "schemaVersion": 1,
              "occurredAt": "2026-06-08T12:39:00Z",
              "source": "pattern-miner",
              "traceId": "trace-mined-0001",
              "payload": {
                "sequence": ["lossOfSignal", "linkDown", "bgpPeerDown"],
                "support": 0.42,
                "confidence": 0.87,
                "lift": 3.1,
                "trailId": "TRAIL-0001",
                "timing": {
                  "timeframeMs": 9000,
                  "medianInterArrivalMs": 4500,
                  "maxInterArrivalMs": 6000,
                  "stddevInterArrivalMs": 1200
                },
                "provenance": {
                  "sourceWindowId": "TXN-0001",
                  "snapshotId": "SNAP-2026-06-08-001",
                  "domain": "core-ip",
                  "codebookVersion": "CODEBOOK-2026-06-08-001"
                }
              }
            }
            """;

    @Mock
    private PatternEnrichmentService enrichmentService;
    @Mock
    private PatternStoreService patternStoreService;
    @Mock
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Acknowledgment ack;

    private final KafkaTopicProperties topics = new KafkaTopicProperties(
            "patterns.mined", "patterns.mined.dlq", "patterns.discovered", "patterns.approved");

    private EventCodec codec;
    private ObjectMapper objectMapper;
    private MinedPatternConsumer consumer;

    @BeforeEach
    void setUp() {
        codec = new EventCodec();
        objectMapper = new ObjectMapper();
        consumer = new MinedPatternConsumer(codec, objectMapper, enrichmentService,
                patternStoreService, kafkaTemplate, topics);
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("patterns.mined", 0, 0L, "TRAIL-0001", value);
    }

    // ---- Happy path (baseline coverage; underpins AC-10/AC-11 assertions) -------------------

    @Test
    void wellFormedFirstDeliveryEnrichesPersistsAndAcks() {
        when(patternStoreService.alreadyProcessed("66666666-6666-4666-8666-666666666666"))
                .thenReturn(false);

        consumer.onMessage(record(VALID_MINED), ack);

        // Enriched + persisted exactly once with the envelope's eventId/source/traceId.
        verify(enrichmentService).enrichAndPersist(any(MinedPatternView.class),
                eq("66666666-6666-4666-8666-666666666666"), eq("pattern-miner"),
                eq("trace-mined-0001"));
        verify(ack).acknowledge();
        // No DLQ on a good message.
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    // ---- AC-10: idempotency — redelivered eventId is a no-op --------------------------------

    // AC-10: the SAME eventId redelivered is skipped via the processed_event dedupe — enrichment /
    // persistence / emit happen at most once; the duplicate is acked without side effects.
    @Test
    void ac10_duplicateEventIdIsSkippedNotPersistedButAcked() {
        // Simulate the second delivery: the eventId is already recorded as processed.
        when(patternStoreService.alreadyProcessed("66666666-6666-4666-8666-666666666666"))
                .thenReturn(true);

        consumer.onMessage(record(VALID_MINED), ack);

        // Dedupe path hit: NO enrichment/persistence, NO emit, but the offset is still acked.
        verify(patternStoreService).alreadyProcessed("66666666-6666-4666-8666-666666666666");
        verifyNoInteractions(enrichmentService);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(ack).acknowledge();
    }

    // AC-10: across two deliveries of the same eventId (first new, then duplicate) enrichment runs
    // exactly once — proving at-least-once redelivery collapses to one persisted pattern.
    @Test
    void ac10_sameEventIdAcrossTwoDeliveriesEnrichesExactlyOnce() {
        when(patternStoreService.alreadyProcessed("66666666-6666-4666-8666-666666666666"))
                .thenReturn(false, true);

        consumer.onMessage(record(VALID_MINED), ack); // first delivery -> processed
        consumer.onMessage(record(VALID_MINED), ack); // redelivery -> skipped

        verify(enrichmentService, times(1)).enrichAndPersist(any(MinedPatternView.class),
                anyString(), anyString(), anyString());
        verify(ack, times(2)).acknowledge();
    }

    // ---- AC-11: DLQ — un-processable records routed to patterns.mined.dlq, not persisted -----

    // AC-11: malformed JSON (deserialization failure) -> patterns.mined.dlq + ack, never persisted.
    @Test
    void ac11_malformedJsonIsRoutedToDlqAndNotPersisted() {
        consumer.onMessage(record("{ this is not valid json"), ack);

        assertDlqRoutedAndAcked();
        verifyNoInteractions(enrichmentService, patternStoreService);
    }

    // AC-11: unsupported schemaVersion (major >= 2) -> DLQ + ack, never persisted.
    @Test
    void ac11_unsupportedSchemaVersionMajorIsRoutedToDlq() {
        String v2 = VALID_MINED.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        consumer.onMessage(record(v2), ack);

        assertDlqRoutedAndAcked();
        verifyNoInteractions(enrichmentService, patternStoreService);
    }

    // AC-11: a valid envelope carrying the WRONG event type on patterns.mined -> DLQ + ack.
    @Test
    void ac11_unexpectedEventTypeIsRoutedToDlq() {
        // A contract-valid PatternDiscoveredEvent-shaped type is not what patterns.mined expects.
        String wrongType = VALID_MINED.replace("\"type\": \"PatternMinedEvent\"",
                "\"type\": \"UnknownMysteryEvent\"");

        consumer.onMessage(record(wrongType), ack);

        // Unknown type is rejected by the codec (CodecException/UnknownEventTypeException) -> DLQ.
        assertDlqRoutedAndAcked();
        verifyNoInteractions(enrichmentService, patternStoreService);
    }

    // AC-11: the DLQ record preserves the original key + value and carries error diagnostics.
    @Test
    void ac11_dlqRecordPreservesKeyValueAndCarriesErrorHeaders() {
        consumer.onMessage(record("{ broken"), ack);

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("patterns.mined.dlq");
        assertThat(sent.key()).isEqualTo("TRAIL-0001");
        assertThat(sent.value()).isEqualTo("{ broken");
        assertThat(sent.headers().lastHeader("error")).isNotNull();
        assertThat(sent.headers().lastHeader("errorClass")).isNotNull();
        verify(ack).acknowledge();
    }

    private void assertDlqRoutedAndAcked() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("patterns.mined.dlq");
        verify(ack).acknowledge();
    }
}
