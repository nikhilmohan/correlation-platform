package com.acp.correlationengine.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.correlationengine.integration.DlqProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Observability adapters exercised for real: the {@link DlqProducer} routing contract (AC19 — poison
 * messages are re-published to {@code <topic>.dlq} with diagnostic headers, never dropped, and the
 * DLQ counter ticks) and the {@link MicrometerCorrelationMetrics} counters/gauge registering the
 * spec-named meters against a real Micrometer registry.
 */
class DlqAndMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void dlq_routesToDlqTopic_withDiagnosticHeaders_andCountsIt() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        CorrelationMetrics metrics = mock(CorrelationMetrics.class);
        DlqProducer dlq = new DlqProducer(kafka, metrics);

        dlq.route("codebook.generated", "k1", "{bad json", new IllegalStateException("boom"));

        ArgumentCaptor<ProducerRecord<String, String>> rec = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(rec.capture());
        ProducerRecord<String, String> record = rec.getValue();
        assertThat(record.topic()).isEqualTo("codebook.generated.dlq");
        assertThat(record.key()).isEqualTo("k1");
        assertThat(record.value()).isEqualTo("{bad json");
        assertThat(headerValue(record, "x-source-topic")).isEqualTo("codebook.generated");
        assertThat(headerValue(record, "x-exception")).isEqualTo("boom");
        verify(metrics).incrementDlqRouted();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dlq_nullError_stillRoutesWithUnknownExceptionHeader() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        DlqProducer dlq = new DlqProducer(kafka, mock(CorrelationMetrics.class));

        dlq.route("patterns.approved", null, "raw", null);

        ArgumentCaptor<ProducerRecord<String, String>> rec = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(rec.capture());
        assertThat(rec.getValue().topic()).isEqualTo("patterns.approved.dlq");
        assertThat(headerValue(rec.getValue(), "x-exception")).isEqualTo("unknown");
    }

    @Test
    void micrometerMetrics_registerSpecNamedMetersAndIncrement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCorrelationMetrics metrics = new MicrometerCorrelationMetrics(registry);

        metrics.incrementAlarmsProcessed();
        metrics.incrementIncidentsCreated();
        metrics.incrementPatternMatch();
        metrics.incrementCodebookMatch();
        metrics.incrementSessionExpiration();
        metrics.incrementDlqRouted();
        metrics.incrementCodebookFetchFailure();
        metrics.incrementStatusChanged("correlated");
        metrics.setActiveInstances(4);

        assertThat(registry.get("alarms_processed_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("incidents_created_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("pattern_match_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("codebook_match_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("instance_session_expirations_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dlq_routed_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("codebook_fetch_failure_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("alarms_status_changed_total").tag("newStatus", "correlated")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("active_instances").gauge().value()).isEqualTo(4.0);
    }

    private static String headerValue(ProducerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
