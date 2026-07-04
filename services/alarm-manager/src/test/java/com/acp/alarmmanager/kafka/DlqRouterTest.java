package com.acp.alarmmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.acp.alarmmanager.service.AmMetrics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/** The DLQ router preserves raw bytes + metadata headers and never throws on send failure. */
class DlqRouterTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
    private final AmMetrics metrics = Mockito.mock(AmMetrics.class);
    private DlqRouter router;

    @BeforeEach
    void setUp() {
        router = new DlqRouter(kafka, metrics);
    }

    @Test
    void routesRawBytesWithMetadataHeaders() {
        byte[] raw = "{\"bad\":true}".getBytes(StandardCharsets.UTF_8);

        router.route("alarms.enriched.live.dlq", "alarms.enriched.live", "k", raw,
                new IllegalArgumentException("missing alarmId"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("alarms.enriched.live.dlq");
        assertThat(record.key()).isEqualTo("k");
        assertThat(record.value()).isEqualTo("{\"bad\":true}");
        assertThat(new String(record.headers().lastHeader("x-dlq-reason").value(),
                StandardCharsets.UTF_8)).contains("missing alarmId");
        assertThat(new String(record.headers().lastHeader("x-dlq-source-topic").value(),
                StandardCharsets.UTF_8)).isEqualTo("alarms.enriched.live");
        verify(metrics).dlqRouted("alarms.enriched.live.dlq");
    }

    @Test
    void neverThrowsWhenSendFails() {
        doThrow(new RuntimeException("broker down")).when(kafka).send(any(ProducerRecord.class));

        // Must not propagate — a DLQ failure is logged, not thrown.
        router.route("alarms.status.changed.dlq", "alarms.status.changed", "k",
                "x".getBytes(StandardCharsets.UTF_8), new RuntimeException("boom"));
    }
}
