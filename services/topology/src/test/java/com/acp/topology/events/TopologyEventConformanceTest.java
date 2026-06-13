package com.acp.topology.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.TopologyChangedEvent;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.GraphEdge;
import com.acp.topology.graph.GraphVertex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * AC-17: the emitted {@code topology.changed} event validates against the FROZEN
 * {@code envelope.schema.json} + {@code TopologyChangedEvent.schema.json} bundled by
 * {@code libs/event-model} (the schemas are on this service's classpath under {@code /schema/},
 * shipped inside the event-model jar). All required fields present: envelope {@code eventId, type,
 * schemaVersion, occurredAt, source, traceId, payload} and payload {@code snapshotId, changeType,
 * nodes, edges}.
 */
class TopologyEventConformanceTest {

    private static final String SCHEMA_ID_PREFIX = "https://acp/event-model/";
    private static final String CLASSPATH_PREFIX = "classpath:schema/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(
                    m -> m.mapPrefix(SCHEMA_ID_PREFIX, CLASSPATH_PREFIX)));

    @Test
    void emittedEventValidatesAgainstFrozenSchema() throws Exception {
        EventCodec codec = new EventCodec();
        TopologyEventPublisher publisher = new TopologyEventPublisher(
                noopKafka(), codec, new TopologyProperties());

        List<GraphVertex> vertices = List.of(
                new GraphVertex("Node:PE1", "Node", "core-ip", "SNAP-CONF", "PE1", Map.of()),
                new GraphVertex("Site:LON", "Site", "core-ip", "SNAP-CONF", "London",
                        Map.of("latitude", 51.5)));
        List<GraphEdge> edges = List.of(
                new GraphEdge("Node:PE1", "Site:LON", "LOCATED_AT", "core-ip", "SNAP-CONF",
                        Map.of()));

        TypedEnvelope<TopologyChangedEvent> envelope = publisher.buildEnvelope(
                "11111111-1111-1111-1111-111111111111", "SNAP-CONF", "core-ip", "full-load",
                vertices, edges, "trace-conf");
        String wire = codec.serialize(envelope);
        JsonNode root = mapper.readTree(wire);

        // Envelope conformance (required fields present, discriminator correct).
        assertThat(validate(load("envelope.schema.json"), root)).isEmpty();
        assertThat(root.hasNonNull("eventId")).isTrue();
        assertThat(root.get("type").asText()).isEqualTo("TopologyChangedEvent");
        assertThat(root.hasNonNull("schemaVersion")).isTrue();
        assertThat(root.hasNonNull("occurredAt")).isTrue();
        assertThat(root.hasNonNull("source")).isTrue();
        assertThat(root.hasNonNull("traceId")).isTrue();
        assertThat(root.has("payload")).isTrue();

        // Payload conformance against the frozen TopologyChangedEvent schema.
        JsonNode payload = root.get("payload");
        assertThat(validate(load("payloads/TopologyChangedEvent.schema.json"), payload)).isEmpty();
        assertThat(payload.hasNonNull("snapshotId")).isTrue();
        assertThat(payload.hasNonNull("changeType")).isTrue();
        assertThat(payload.get("nodes").isArray()).isTrue();
        assertThat(payload.get("edges").isArray()).isTrue();
    }

    private JsonSchema load(String relativePath) {
        return factory.getSchema(SchemaLocation.of(SCHEMA_ID_PREFIX + relativePath));
    }

    private static Set<ValidationMessage> validate(JsonSchema schema, JsonNode node) {
        return schema.validate(node);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> noopKafka() {
        return org.mockito.Mockito.mock(KafkaTemplate.class);
    }
}
