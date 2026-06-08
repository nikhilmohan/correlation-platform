package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Criterion 1 (Java side): wire-format agreement Java &harr; Python.
 *
 * <p>Each of the nine shared golden fixtures (the SAME files the Python tests read) is
 * deserialized to a typed object and re-serialized; the re-serialized JSON must be canonically
 * equal to the fixture. Because both bindings assert against the same committed fixtures, this
 * proves the two bindings agree on the wire without a polyglot CI job. This is the deserialize+
 * serialize+round-trip-against-each-fixture requirement.
 */
class WireFormatAgreementTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    static java.util.stream.Stream<String> payloadTypes() {
        return Fixtures.ALL_TYPES.stream();
    }

    @ParameterizedTest(name = "deserialize golden fixture: {0}")
    @MethodSource("payloadTypes")
    void deserializesGoldenFixture(String type) {
        String fixture = Fixtures.read(type);

        TypedEnvelope<Object> env = codec.deserialize(fixture);

        assertEquals(type, env.getType(), "type discriminator");
        assertEquals(1, env.getSchemaVersion(), "schemaVersion");
        assertNotNull(env.getEventId(), "eventId present");
        assertNotNull(env.getPayload(), "payload bound to a typed object");
        // The payload must be exactly the class the registry maps `type` to.
        assertEquals(TypeRegistry.resolve(type), env.getPayload().getClass(), "payload class");
    }

    @ParameterizedTest(name = "serialize matches golden fixture: {0}")
    @MethodSource("payloadTypes")
    void serializesToGoldenFixture(String type) throws Exception {
        String fixture = Fixtures.read(type);

        TypedEnvelope<Object> env = codec.deserialize(fixture);
        String serialized = codec.serialize(env);

        // Canonical equality: parse both and compare the JSON trees (key order / whitespace
        // independent). Proves the re-serialized bytes match the committed golden fixture, i.e.
        // the Java binding produces the SAME wire format the Python binding asserts against.
        JsonNode expected = mapper.readTree(fixture);
        JsonNode actual = mapper.readTree(serialized);
        assertEquals(expected, actual, "re-serialized JSON must equal the golden fixture");
    }
}
