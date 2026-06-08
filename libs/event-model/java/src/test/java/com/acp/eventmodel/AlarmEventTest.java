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
 * Criteria 7, 8, 9 (Java side): AlarmEvent {@code managedObjectId} required, {@code state} enum
 * enforced, optional fields absent OK. Mirrors the Python {@code test_alarm_event.py}.
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
