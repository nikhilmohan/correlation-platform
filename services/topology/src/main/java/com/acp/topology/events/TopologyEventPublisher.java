package com.acp.topology.events;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.Edge;
import com.acp.eventmodel.generated.Node;
import com.acp.eventmodel.generated.TopologyChangedEvent;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.GraphEdge;
import com.acp.topology.graph.GraphVertex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds a {@code TypedEnvelope<TopologyChangedEvent>} (envelope {@code eventId} = idempotency key)
 * and produces it to {@code topology.changed} via the idempotent producer. On unrecoverable send
 * failure the envelope is routed to {@code topology.changed.dlq} (EH-8). The wire payload is exactly
 * the frozen event-model binding (serialized via {@link EventCodec}).
 */
@Component
public class TopologyEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TopologyEventPublisher.class);
    private static final Set<String> ALLOWED_CHANGE_TYPES = Set.of("full-load", "incremental");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec codec;
    private final DlqPublisher dlqPublisher;
    private final TopologyProperties.Kafka config;

    public TopologyEventPublisher(KafkaTemplate<String, String> kafkaTemplate, EventCodec codec,
            DlqPublisher dlqPublisher, TopologyProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.dlqPublisher = dlqPublisher;
        this.config = properties.getKafka();
    }

    /**
     * Emit one {@code topology.changed} event. Returns the {@code eventId} used (the idempotency key).
     *
     * @throws IllegalArgumentException if {@code changeType} is outside {@code {full-load,incremental}}
     */
    public String emit(String snapshotId, String domain, String changeType,
            List<GraphVertex> vertices, List<GraphEdge> edges, String traceId) {
        if (!ALLOWED_CHANGE_TYPES.contains(changeType)) {
            throw new IllegalArgumentException(
                    "changeType must be one of " + ALLOWED_CHANGE_TYPES + " (AC-16): " + changeType);
        }
        String eventId = UUID.randomUUID().toString();
        TypedEnvelope<TopologyChangedEvent> envelope =
                buildEnvelope(eventId, snapshotId, domain, changeType, vertices, edges, traceId);
        String wire = codec.serialize(envelope);
        try {
            kafkaTemplate.send(config.getTopic(), snapshotId, wire).get();
            log.info("emitted topology.changed eventId={} snapshotId={} changeType={}",
                    eventId, snapshotId, changeType);
        } catch (Exception e) {
            log.error("topology.changed send failed; routing to DLQ snapshotId={} traceId={}",
                    snapshotId, traceId, e);
            dlqPublisher.publish(wire, snapshotId, traceId, e);
        }
        return eventId;
    }

    TypedEnvelope<TopologyChangedEvent> buildEnvelope(String eventId, String snapshotId,
            String domain, String changeType, List<GraphVertex> vertices, List<GraphEdge> edges,
            String traceId) {
        TopologyChangedEvent payload = new TopologyChangedEvent();
        payload.setSnapshotId(snapshotId);
        payload.setDomain(domain);
        payload.setChangeType(changeType);
        payload.setNodes(nodeDescriptors(vertices));
        payload.setEdges(edgeDescriptors(edges));
        // Instant.now().toString() yields an ISO-8601 UTC string with a trailing 'Z'.
        return new TypedEnvelope<>(eventId, "TopologyChangedEvent", 1,
                Instant.now().toString(), "topology", traceId, payload);
    }

    private List<Node> nodeDescriptors(List<GraphVertex> vertices) {
        List<Node> out = new ArrayList<>();
        for (GraphVertex v : vertices) {
            Node n = new Node();
            n.setAdditionalProperty("managedObjectId", v.managedObjectId());
            n.setAdditionalProperty("objectType", v.objectType());
            out.add(n);
        }
        return out;
    }

    private List<Edge> edgeDescriptors(List<GraphEdge> edges) {
        List<Edge> out = new ArrayList<>();
        for (GraphEdge e : edges) {
            Edge edge = new Edge();
            edge.setAdditionalProperty("from", e.from());
            edge.setAdditionalProperty("to", e.to());
            edge.setAdditionalProperty("relation", e.relation());
            out.add(edge);
        }
        return out;
    }
}
