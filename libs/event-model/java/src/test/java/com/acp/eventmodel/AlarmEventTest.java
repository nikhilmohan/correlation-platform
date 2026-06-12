package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acp.eventmodel.generated.AlarmEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criteria 7, 8, 9, 9a (Java side): AlarmEvent {@code managedObjectId} required, {@code state} enum
 * enforced, optional fields absent OK, and the canonical {@code alarmType} join key required +
 * round-tripping (distinct from {@code eventType}/{@code probableCause}). Mirrors the Python
 * {@code test_alarm_event.py}.
 */
class AlarmEventTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode alarmPayload() throws Exception {
        return (ObjectNode) mapper.readTree(Fixtures.read("AlarmEvent")).get("payload");
    }

    private String alarmFixtureWithoutPayloadField(String field) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(Fixtures.read("AlarmEvent"));
        ((ObjectNode) root.get("payload")).remove(field);
        return mapper.writeValueAsString(root);
    }

    // Criterion 7 — managedObjectId required.
    @Test
    void missingManagedObjectIdRejected() throws Exception {
        assertThrows(CodecException.class,
                () -> codec.deserialize(alarmFixtureWithoutPayloadField("managedObjectId")));
    }

    // Criterion 8 — state enum enforced.
    @Test
    void invalidStateRejected() throws Exception {
        String json = Fixtures.read("AlarmEvent").replace("\"state\": \"raised\"",
                "\"state\": \"flapping\"");
        assertThrows(CodecException.class, () -> codec.deserialize(json));
    }

    @ParameterizedTest(name = "valid state accepted: {0}")
    @ValueSource(strings = {"raised", "cleared"})
    void acceptsValidStates(String state) {
        String json = Fixtures.read("AlarmEvent").replace("\"state\": \"raised\"",
                "\"state\": \"" + state + "\"");
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(json));
        AlarmEvent alarm = (AlarmEvent) env.getPayload();
        assertEquals(state, alarm.getState().value());
    }

    // Criterion 9a — canonical alarmType join key required + round-trips.
    // Mirrors Python test_alarm_event.py::test_alarm_type_present_round_trips.
    @Test
    void alarmTypePresentRoundTrips() throws Exception {
        TypedEnvelope<Object> env = codec.deserialize(Fixtures.read("AlarmEvent"));
        AlarmEvent alarm = (AlarmEvent) env.getPayload();
        assertEquals("LinkDown", alarm.getAlarmType(),
                "canonical alarmType join key bound from the fixture");
        // The canonical join key is distinct from the X.733 eventType / probableCause.
        org.junit.jupiter.api.Assertions.assertNotEquals(alarm.getAlarmType(), alarm.getEventType());
        org.junit.jupiter.api.Assertions.assertNotEquals(alarm.getAlarmType(), alarm.getProbableCause());
        // And it survives re-serialization byte-equal to the golden fixture value.
        String wire = codec.serialize(env);
        assertEquals("LinkDown", mapper.readTree(wire).get("payload").get("alarmType").asText());
    }

    // Criterion 9a — alarmType is REQUIRED on AlarmEvent; absence raises.
    // Mirrors Python test_alarm_event.py::test_alarm_type_required.
    @Test
    void missingAlarmTypeRejected() throws Exception {
        assertThrows(CodecException.class,
                () -> codec.deserialize(alarmFixtureWithoutPayloadField("alarmType")));
    }

    // Criterion 9 — optional fields (clearedAt, vendorRaw) absent OK.
    @Test
    void optionalFieldsAbsentOk() {
        // The golden fixture already omits clearedAt and vendorRaw.
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("AlarmEvent")));
        AlarmEvent alarm = (AlarmEvent) env.getPayload();
        assertNull(alarm.getClearedAt(), "clearedAt absent -> null");
        assertNull(alarm.getVendorRaw(), "vendorRaw absent -> null");
        // And they are omitted (not null) on the wire after re-serialization.
        String wire = codec.serialize(env);
        org.junit.jupiter.api.Assertions.assertFalse(wire.contains("clearedAt"),
                "absent clearedAt omitted on the wire");
        org.junit.jupiter.api.Assertions.assertFalse(wire.contains("vendorRaw"),
                "absent vendorRaw omitted on the wire");
    }
}
