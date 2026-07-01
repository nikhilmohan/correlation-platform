package com.acp.patternmanager.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.patternmanager.config.KafkaTopicProperties;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SequenceElementEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Event emission: PatternDiscoveredEvent/PatternApprovedEvent round-trip via the FROZEN codec, carry
 * a valid sessionWindow, and carry NO structural-validation field (criteria 6, 8, 19, 20).
 */
@ExtendWith(MockitoExtension.class)
class PatternEventPublisherTest {

    @Mock
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate;

    private EventCodec codec;
    private ObjectMapper objectMapper;
    private PatternEventPublisher publisher;
    private final KafkaTopicProperties topics = new KafkaTopicProperties(
            "patterns.mined", "patterns.mined.dlq", "patterns.discovered", "patterns.approved");

    @BeforeEach
    void setUp() {
        codec = new EventCodec();
        objectMapper = new ObjectMapper();
        publisher = new PatternEventPublisher(codec, kafkaTemplate, topics, objectMapper);
    }

    private PatternEntity persistedPattern() {
        PatternEntity e = new PatternEntity();
        e.setPatternId(UUID.randomUUID());
        e.setTrailId("trail-1");
        e.setRootCauseAlarmType("LOS");
        e.setSupport(0.4);
        e.setConfidence(0.9);
        e.setLift(3.2);
        e.setTimingJson("{\"timeframeMs\":3000,\"medianInterArrivalMs\":1000}");
        e.setReconcileStatus("unexplained");
        e.setStructurallyValidated(false);
        e.setStructuralValidationReason("objects [R7:1] not reachable");
        e.setSessionWindowMs(5000);
        e.setSessionWindowType("gap-based");
        e.setInstanceCount(2);
        e.setLifecycle("draft");
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        e.getSequenceElements().add(new SequenceElementEntity(UUID.randomUUID(), e, 0, "LOS", false));
        e.getSequenceElements().add(new SequenceElementEntity(UUID.randomUUID(), e, 1, "LinkDown", false));
        return e;
    }

    private String captureSentValue() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), value.capture());
        return value.getValue();
    }

    // Criteria 6 + 19: discovered event round-trips, is draft, carries sessionWindow, no struct field.
    @Test
    void discoveredEventRoundTripsIsDraftCarriesSessionWindowNoStructField() {
        PatternEntity e = persistedPattern();
        publisher.publishDiscovered(e, "trace-1");

        verify(kafkaTemplate).send(eq("patterns.discovered"), eq(e.getPatternId().toString()), anyString());
        String json = captureSentValue();

        // Round-trips via the frozen binding (envelope + payload + schema validation succeed).
        TypedEnvelope<Object> env = codec.deserialize(json);
        assertThat(env.getType()).isEqualTo("PatternDiscoveredEvent");
        assertThat(json).contains("\"lifecycle\":\"draft\"");
        assertThat(json).contains("\"sessionWindow\"");
        assertThat(json).contains("\"windowMs\":5000");
        assertThat(json).contains("\"type\":\"gap-based\"");
        // The internal structural-validation flag must NOT be on the wire (frozen schema).
        assertThat(json).doesNotContain("structurallyValidated");
        assertThat(json).doesNotContain("structuralValidationReason");
    }

    // Criteria 8 + 20: approved event round-trips, is approved, sessionWindow == persisted, no struct.
    @Test
    void approvedEventSessionWindowEqualsPersistedAndValidatesNoStructField() {
        PatternEntity e = persistedPattern();
        e.setLifecycle("approved");
        publisher.publishApproved(e, "trace-2");

        verify(kafkaTemplate).send(eq("patterns.approved"), eq(e.getPatternId().toString()), anyString());
        String json = captureSentValue();

        TypedEnvelope<Object> env = codec.deserialize(json);
        assertThat(env.getType()).isEqualTo("PatternApprovedEvent");
        assertThat(json).contains("\"lifecycle\":\"approved\"");
        // sessionWindow equals the persisted row value (windowMs=5000, gap-based).
        assertThat(json).contains("\"windowMs\":5000");
        assertThat(json).contains("\"type\":\"gap-based\"");
        assertThat(json).doesNotContain("structurallyValidated");
    }
}
