package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acp.eventmodel.generated.AlarmStatusChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AlarmStatusChange (Java side): typed round-trip, required-field enforcement, the
 * {@code newStatus} enum (incl. the hyphenated wire values {@code in-progress}/{@code reverted-open}),
 * additionalProperties rejection, and registry resolution. Mirrors the Python
 * {@code test_alarm_status_change.py} against the SAME shared golden fixture.
 *
 * <p>AlarmStatusChange is the generic alarm-lifecycle status-change event carried on
 * {@code alarms.status.changed}: any service may fire it, and the Alarm Manager consumes it to keep
 * live alarm status in sync. It carries no correlation context (that lives on
 * CorrelationResultEvent).
 */
class AlarmStatusChangeTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private String mutate(java.util.function.Consumer<ObjectNode> payloadEdit) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Fixtures.read("AlarmStatusChange"));
            payloadEdit.accept((ObjectNode) root.get("payload"));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void typedRoundTrip() throws Exception {
        // The golden fixture deserializes to a typed payload and re-serializes losslessly.
        String fixture = Fixtures.read("AlarmStatusChange");
        TypedEnvelope<Object> env = codec.deserialize(fixture);

        AlarmStatusChange payload = (AlarmStatusChange) env.getPayload();
        assertEquals("ALM-0001", payload.getAlarmId());
        assertEquals(AlarmStatusChange.NewStatus.CORRELATED, payload.getNewStatus());
        assertEquals("correlation-engine", payload.getSource());
        assertEquals("2026-06-08T12:45:00Z", payload.getChangedAt());

        JsonNode expected = mapper.readTree(fixture);
        JsonNode actual = mapper.readTree(codec.serialize(env));
        assertEquals(expected, actual, "re-serialized JSON must equal the golden fixture");
    }

    @ParameterizedTest(name = "missing required field {0} rejected")
    @ValueSource(strings = {"alarmId", "newStatus", "source", "changedAt"})
    void missingRequiredFieldRejected(String field) {
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p -> p.remove(field))));
    }

    @ParameterizedTest(name = "newStatus accepts wire value {0}")
    @ValueSource(strings = {"open", "in-progress", "correlated", "cleared", "reverted-open"})
    void newStatusEnumAcceptsAllFiveValues(String status) throws Exception {
        TypedEnvelope<Object> env =
                codec.deserialize(mutate(p -> p.put("newStatus", status)));
        AlarmStatusChange payload = (AlarmStatusChange) env.getPayload();

        // The enum value() exposes the EXACT JSON wire string (incl. the hyphenated values).
        assertEquals(status, payload.getNewStatus().value(), "enum maps to its exact wire value");
        // And it survives re-serialization unchanged (hyphenated values stay hyphenated).
        JsonNode wire = mapper.readTree(codec.serialize(env));
        assertEquals(status, wire.get("payload").get("newStatus").asText(),
                "wire value round-trips exactly");
    }

    @Test
    void newStatusEnumRejectsInvalidValue() {
        // `flapping` is not one of the five lifecycle statuses; schema enum validation rejects it.
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("newStatus", "flapping"))));
    }

    @Test
    void additionalPropertiesRejected() {
        // additionalProperties:false — no correlation context (e.g. incidentId) belongs here.
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("incidentId", "INC-0001"))));
    }

    @Test
    void registryResolvesAlarmStatusChange() {
        assertSame(AlarmStatusChange.class, TypeRegistry.resolve("AlarmStatusChange"),
                "registry resolves the discriminator to its payload class");
        // End-to-end via the codec discriminator.
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("AlarmStatusChange")));
        assertSame(AlarmStatusChange.class, env.getPayload().getClass());
    }
}
