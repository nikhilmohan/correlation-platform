package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.acp.eventmodel.generated.CodebookGeneratedEvent;
import com.acp.eventmodel.generated.PatternMinedEvent;
import com.acp.eventmodel.generated.TopologyChangedEvent;
import com.acp.eventmodel.generated.TrailsBuiltEvent;
import com.acp.eventmodel.generated.TransactionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract change (Java side): the OPTIONAL {@code domain} field added alongside {@code snapshotId}
 * on the snapshot-referencing payloads — {@code TopologyChangedEvent}, {@code TrailsBuiltEvent},
 * {@code CodebookGeneratedEvent}, {@code TransactionEvent} (top-level) and
 * {@code PatternMinedEvent.provenance} (nested).
 *
 * <p>For each payload {@code domain} is OPTIONAL/backward-compatible: present AND absent are both
 * valid, and when present the value round-trips on the wire. Mirrors the Python parity tests
 * ({@code test_topology_changed.py}, {@code test_trails_built.py}, {@code test_codebook_generated.py},
 * {@code test_transaction.py}, {@code test_pattern_mined.py}) against the SAME shared golden fixtures
 * (which now carry {@code domain: "core-ip"}).
 */
class OptionalDomainTest {

    private static final EventCodec CODEC = new EventCodec();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Returns the fixture JSON with the named top-level payload field removed. */
    private static String withoutPayloadField(String type, String field) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.read(type));
            ((ObjectNode) root.get("payload")).remove(field);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the {@code payload.domain} string of the re-serialized envelope (null if absent). */
    private static String roundTripDomain(String type) throws Exception {
        TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read(type));
        JsonNode payload = MAPPER.readTree(CODEC.serialize(env)).get("payload");
        return payload.has("domain") ? payload.get("domain").asText() : null;
    }

    @Nested
    class TopologyChanged {
        @Test
        void domainPresentRoundTrips() throws Exception {
            TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read("TopologyChangedEvent"));
            assertEquals("core-ip", ((TopologyChangedEvent) env.getPayload()).getDomain());
            assertEquals("core-ip", roundTripDomain("TopologyChangedEvent"));
        }

        @Test
        void domainAbsentIsOptional() throws Exception {
            TypedEnvelope<Object> env = assertDoesNotThrow(
                    () -> CODEC.deserialize(withoutPayloadField("TopologyChangedEvent", "domain")));
            assertNull(((TopologyChangedEvent) env.getPayload()).getDomain());
            // Absent domain is omitted on the wire (canonical NON_NULL output).
            JsonNode payload = MAPPER.readTree(CODEC.serialize(env)).get("payload");
            assertFalse(payload.has("domain"), "absent domain omitted on the wire");
        }
    }

    @Nested
    class TrailsBuilt {
        @Test
        void domainPresentRoundTrips() throws Exception {
            TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read("TrailsBuiltEvent"));
            assertEquals("core-ip", ((TrailsBuiltEvent) env.getPayload()).getDomain());
            assertEquals("core-ip", roundTripDomain("TrailsBuiltEvent"));
        }

        @Test
        void domainAbsentIsOptional() throws Exception {
            TypedEnvelope<Object> env = assertDoesNotThrow(
                    () -> CODEC.deserialize(withoutPayloadField("TrailsBuiltEvent", "domain")));
            assertNull(((TrailsBuiltEvent) env.getPayload()).getDomain());
            JsonNode payload = MAPPER.readTree(CODEC.serialize(env)).get("payload");
            assertFalse(payload.has("domain"), "absent domain omitted on the wire");
        }
    }

    @Nested
    class CodebookGenerated {
        @Test
        void domainPresentRoundTrips() throws Exception {
            TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read("CodebookGeneratedEvent"));
            assertEquals("core-ip", ((CodebookGeneratedEvent) env.getPayload()).getDomain());
            assertEquals("core-ip", roundTripDomain("CodebookGeneratedEvent"));
        }

        @Test
        void domainAbsentIsOptional() throws Exception {
            TypedEnvelope<Object> env = assertDoesNotThrow(
                    () -> CODEC.deserialize(withoutPayloadField("CodebookGeneratedEvent", "domain")));
            assertNull(((CodebookGeneratedEvent) env.getPayload()).getDomain());
            JsonNode payload = MAPPER.readTree(CODEC.serialize(env)).get("payload");
            assertFalse(payload.has("domain"), "absent domain omitted on the wire");
        }
    }

    @Nested
    class Transaction {
        @Test
        void domainPresentRoundTrips() throws Exception {
            TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read("TransactionEvent"));
            assertEquals("core-ip", ((TransactionEvent) env.getPayload()).getDomain());
            assertEquals("core-ip", roundTripDomain("TransactionEvent"));
        }

        @Test
        void domainAbsentIsOptional() throws Exception {
            TypedEnvelope<Object> env = assertDoesNotThrow(
                    () -> CODEC.deserialize(withoutPayloadField("TransactionEvent", "domain")));
            assertNull(((TransactionEvent) env.getPayload()).getDomain());
            JsonNode payload = MAPPER.readTree(CODEC.serialize(env)).get("payload");
            assertFalse(payload.has("domain"), "absent domain omitted on the wire");
        }
    }

    /** PatternMinedEvent carries the optional {@code domain} NESTED inside {@code provenance}. */
    @Nested
    class PatternMinedProvenance {
        private String withoutProvenanceDomain() {
            try {
                ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.read("PatternMinedEvent"));
                ((ObjectNode) root.get("payload").get("provenance")).remove("domain");
                return MAPPER.writeValueAsString(root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        void domainPresentRoundTrips() throws Exception {
            TypedEnvelope<Object> env = CODEC.deserialize(Fixtures.read("PatternMinedEvent"));
            assertEquals("core-ip",
                    ((PatternMinedEvent) env.getPayload()).getProvenance().getDomain());
            JsonNode provenance =
                    MAPPER.readTree(CODEC.serialize(env)).get("payload").get("provenance");
            assertEquals("core-ip", provenance.get("domain").asText());
        }

        @Test
        void domainAbsentIsOptional() throws Exception {
            TypedEnvelope<Object> env =
                    assertDoesNotThrow(() -> CODEC.deserialize(withoutProvenanceDomain()));
            assertNull(((PatternMinedEvent) env.getPayload()).getProvenance().getDomain());
            JsonNode provenance =
                    MAPPER.readTree(CODEC.serialize(env)).get("payload").get("provenance");
            assertFalse(provenance.has("domain"), "absent provenance.domain omitted on the wire");
        }
    }
}
