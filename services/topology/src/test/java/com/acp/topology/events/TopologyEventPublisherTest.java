package com.acp.topology.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.TopologyChangedEvent;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.GraphEdge;
import com.acp.topology.graph.GraphVertex;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * AC-15 (first ingest emits full-load; payload deserialises to the frozen binding; snapshotId
 * matches), AC-16 (changeType is never outside {full-load, incremental}). The KafkaTemplate is
 * mocked; the codec is the REAL frozen event-model codec, so the wire bytes are exactly the
 * contract binding.
 */
@ExtendWith(MockitoExtension.class)
class TopologyEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<String> wireCaptor;

    private TopologyEventPublisher publisher;
    private final EventCodec codec = new EventCodec();

    @BeforeEach
    void setUp() {
        TopologyProperties properties = new TopologyProperties();
        publisher = new TopologyEventPublisher(kafkaTemplate, codec,
                new DlqPublisher(kafkaTemplate, properties), properties);
    }

    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    @Test
    void firstIngestEmitsFullLoad_payloadDeserialises_idMatches() {
        stubSuccessfulSend();
        List<GraphVertex> vertices = List.of(new GraphVertex("Node:PE1", "Node", "core-ip",
                "SNAP-1", "PE1", Map.of()));
        List<GraphEdge> edges = List.of(new GraphEdge("Port:p1", "Node:PE1", "HOSTED_ON",
                "core-ip", "SNAP-1", Map.of()));

        String eventId = publisher.emit("SNAP-1", "core-ip", "full-load", vertices, edges, "trace-1");
        assertThat(eventId).isNotBlank();

        verify(kafkaTemplate).send(eq("topology.changed"), eq("SNAP-1"), wireCaptor.capture());
        String wire = wireCaptor.getValue();

        // The emitted bytes deserialise against the FROZEN event-model binding (AC-15).
        TypedEnvelope<Object> envelope = codec.deserialize(wire);
        assertThat(envelope.getType()).isEqualTo("TopologyChangedEvent");
        assertThat(envelope.getEventId()).isEqualTo(eventId);
        assertThat(envelope.getSource()).isEqualTo("topology");
        assertThat(envelope.getSchemaVersion()).isEqualTo(1);

        TopologyChangedEvent payload = (TopologyChangedEvent) envelope.getPayload();
        assertThat(payload.getChangeType()).isEqualTo("full-load");
        assertThat(payload.getSnapshotId()).isEqualTo("SNAP-1"); // matches API response id
        assertThat(payload.getNodes()).hasSize(1);
        assertThat(payload.getEdges()).hasSize(1);
    }

    @Test
    void emitsIncrementalWhenRequested() {
        stubSuccessfulSend();
        String eventId = publisher.emit("SNAP-2", "core-ip", "incremental", List.of(), List.of(),
                "trace-2");
        assertThat(eventId).isNotBlank();
        verify(kafkaTemplate).send(eq("topology.changed"), eq("SNAP-2"), anyString());
    }

    @Test
    void neverEmitsChangeTypeOutsideFullLoadOrIncremental() {
        // AC-16: delete (or any other value) is rejected by the publisher guard — never produced.
        assertThatThrownBy(() -> publisher.emit("SNAP-3", "core-ip", "delete", List.of(), List.of(),
                "trace-3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.emit("SNAP-3", "core-ip", "rubbish", List.of(),
                List.of(), "trace-3"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void routesToDlqOnSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // emit() does not propagate the send failure; it routes to the DLQ topic.
        String eventId = publisher.emit("SNAP-4", "core-ip", "full-load", List.of(), List.of(),
                "trace-4");
        assertThat(eventId).isNotBlank();

        ArgumentCaptor<ProducerRecord<String, String>> dlq =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(dlq.capture());
        assertThat(dlq.getValue().topic()).isEqualTo("topology.changed.dlq");
        assertThat(dlq.getValue().headers().lastHeader("x-original-topic")).isNotNull();
    }
}
