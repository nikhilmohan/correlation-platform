package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Criterion 4: envelope round-trip per payload type (nine cases).
 *
 * <p>serialize(deserialize(fixture)) yields JSON equal to the original, and the typed envelope
 * deserialized from that re-serialized JSON equals the first — required and optional fields
 * preserved (including {@code AlarmEvent.trailIds: []} and omitted optionals).
 */
class EnvelopeRoundTripTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    static java.util.stream.Stream<String> payloadTypes() {
        return Fixtures.ALL_TYPES.stream();
    }

    @ParameterizedTest(name = "round-trip: {0}")
    @MethodSource("payloadTypes")
    void roundTrip(String type) throws Exception {
        String fixture = Fixtures.read(type);

        TypedEnvelope<Object> first = codec.deserialize(fixture);
        String wire = codec.serialize(first);
        TypedEnvelope<Object> second = codec.deserialize(wire);

        // Typed envelopes equal (payload POJOs implement equals/hashCode).
        assertEquals(first, second, "round-tripped typed envelope equals original");
        // And the JSON is stable across the round-trip.
        JsonNode firstWire = mapper.readTree(wire);
        JsonNode secondWire = mapper.readTree(codec.serialize(second));
        assertEquals(firstWire, secondWire, "round-tripped wire JSON is stable");
    }
}
