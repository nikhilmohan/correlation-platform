package com.acp.enrichment.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.enrichment.pipeline.EnrichmentPipeline;
import com.acp.enrichment.pipeline.Path;
import com.acp.eventmodel.CodecException;
import com.acp.eventmodel.SchemaVersionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

/** Acceptance criterion 15 — poison + unknown-schemaVersion messages routed to DLQ; continue. */
@ExtendWith(MockitoExtension.class)
class DlqRoutingTest {

    private final EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());

    @Mock
    EnrichmentPipeline pipeline;
    @Mock
    DlqRouter dlqRouter;
    @Mock
    Acknowledgment ack;

    private static final String VALID = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"type\":\"AlarmEvent\",\"schemaVersion\":1,\"occurredAt\":\"2026-06-11T10:00:00Z\","
            + "\"source\":\"nms-alpha\",\"traceId\":\"t\",\"payload\":{\"alarmId\":\"a\"}}";

    @Test
    void malformedJsonThrowsCodecException() {
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(CodecException.class);
    }

    @Test
    void unknownMajorSchemaVersionThrowsSchemaVersionException() {
        String v2 = VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertThatThrownBy(() -> parser.parse(v2)).isInstanceOf(SchemaVersionException.class);
    }

    @Test
    void missingRequiredEnvelopeFieldThrowsCodecException() {
        String noSource = VALID.replace("\"source\":\"nms-alpha\",", "");
        assertThatThrownBy(() -> parser.parse(noSource)).isInstanceOf(CodecException.class);
    }

    @Test
    void consumerRoutesMalformedToDlqThenContinues() {
        EventIdDedupe dedupe = new EventIdDedupe();
        AlarmConsumer consumer = new AlarmConsumer(parser, pipeline, dlqRouter, dedupe,
                new SimpleMeterRegistry());

        consumer.onHistory("{not json", ack);

        verify(dlqRouter).route(ArgumentMatchers.eq(Path.HISTORY),
                ArgumentMatchers.eq("{not json"), ArgumentMatchers.eq("deserialize"),
                ArgumentMatchers.anyString());
        verify(pipeline, never()).process(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        verify(ack).acknowledge(); // committed so the partition keeps moving
    }

    @Test
    void consumerRoutesUnknownSchemaVersionToDlq() {
        EventIdDedupe dedupe = new EventIdDedupe();
        AlarmConsumer consumer = new AlarmConsumer(parser, pipeline, dlqRouter, dedupe,
                new SimpleMeterRegistry());
        String v2 = VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        consumer.onLive(v2, ack);

        verify(dlqRouter).route(ArgumentMatchers.eq(Path.LIVE), ArgumentMatchers.eq(v2),
                ArgumentMatchers.eq("schema_version"), ArgumentMatchers.anyString());
        verify(ack).acknowledge();
    }

    @Test
    void redeliveredEventIdIsDeduped() {
        EventIdDedupe dedupe = new EventIdDedupe();
        assertThat(dedupe.firstSeen("e-1")).isTrue();
        assertThat(dedupe.firstSeen("e-1")).isFalse();
    }
}
