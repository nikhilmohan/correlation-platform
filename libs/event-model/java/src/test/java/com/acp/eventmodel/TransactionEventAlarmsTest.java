package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acp.eventmodel.generated.Alarm;
import com.acp.eventmodel.generated.TransactionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Java half of the enriched-TransactionEvent contract: the typed ordered {@code alarms[]} detail.
 * Mirrors the Python {@code test_transaction.py} additions so the two bindings agree on the wire
 * (criterion 1). Each {@code alarms[]} entry reuses the SAME managedObjectId + perceivedSeverity
 * representations the {@code AlarmEvent} binding uses (plain wire strings); the entry's required
 * fields, strictness and managedObjectId scheme are enforced by schema validation in the codec.
 */
class TransactionEventAlarmsTest {

    /** The five fields each {@code alarms[]} entry must carry (mirrored from AlarmEvent). */
    private static final List<String> ALARM_REQUIRED =
            List.of("alarmId", "eventType", "raisedAt", "managedObjectId", "perceivedSeverity");

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture() {
        return Fixtures.read("TransactionEvent");
    }

    /** Mutate the first {@code alarms[]} entry of the golden fixture and re-serialize to wire JSON. */
    private String fixtureWithFirstAlarm(java.util.function.Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(fixture());
        ObjectNode firstAlarm =
                (ObjectNode) ((ArrayNode) root.get("payload").get("alarms")).get(0);
        mutate.accept(firstAlarm);
        return mapper.writeValueAsString(root);
    }

    // alarms[] deserializes to typed per-alarm detail mirroring AlarmEvent fields.
    @Test
    void alarmsTypedDetail() {
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(fixture()));
        TransactionEvent txn = (TransactionEvent) env.getPayload();
        List<Alarm> alarms = txn.getAlarms();
        assertEquals(3, alarms.size());
        Alarm first = alarms.get(0);
        assertInstanceOf(Alarm.class, first);
        assertEquals("ALM-0001", first.getAlarmId());
        assertEquals("communicationsAlarm", first.getEventType());
        assertEquals("Port:PE1-LC2-P3", first.getManagedObjectId());
        assertEquals("critical", first.getPerceivedSeverity());
        assertTrue(first.getRaisedAt().startsWith("2026-06-08T12:30:05"));
    }

    // alarms[] is ORDERED — the Pattern Miner depends on sequence preservation, in and out.
    @Test
    void alarmsOrderPreserved() throws Exception {
        TypedEnvelope<Object> env = codec.deserialize(fixture());
        TransactionEvent txn = (TransactionEvent) env.getPayload();
        assertEquals(List.of("ALM-0001", "ALM-0002", "ALM-0003"),
                txn.getAlarms().stream().map(Alarm::getAlarmId).collect(Collectors.toList()));

        // Re-serialize and confirm the wire order is unchanged (no reordering on output).
        JsonNode out = mapper.readTree(codec.serialize(env));
        List<String> wireOrder = StreamSupport
                .stream(out.get("payload").get("alarms").spliterator(), false)
                .map(n -> n.get("alarmId").asText())
                .collect(Collectors.toList());
        assertEquals(List.of("ALM-0001", "ALM-0002", "ALM-0003"), wireOrder);
    }

    // alarms[] round-trips canonically equal to the golden fixture (cross-binding anchor).
    @Test
    void alarmsRoundTripMatchesFixture() throws Exception {
        JsonNode expected = mapper.readTree(fixture());
        TypedEnvelope<Object> env = codec.deserialize(fixture());
        JsonNode actual = mapper.readTree(codec.serialize(env));
        assertEquals(expected.get("payload").get("alarms"), actual.get("payload").get("alarms"),
                "alarms[] must round-trip byte-equal to the golden fixture");
    }

    // Each alarms[] entry requires all five fields (additionalProperties:false on the entry).
    @ParameterizedTest(name = "missing alarms[].{0} rejected")
    @ValueSource(strings = {"alarmId", "eventType", "raisedAt", "managedObjectId", "perceivedSeverity"})
    void alarmEntryMissingFieldRejected(String field) {
        assertEquals(5, ALARM_REQUIRED.size());
        assertThrows(CodecException.class,
                () -> codec.deserialize(fixtureWithFirstAlarm(a -> a.remove(field))));
    }

    // alarms[] entries are strict — unknown fields are rejected.
    @Test
    void alarmEntryExtraFieldRejected() {
        assertThrows(CodecException.class,
                () -> codec.deserialize(fixtureWithFirstAlarm(a -> a.put("unexpected", "x"))));
    }

    // alarms[].managedObjectId reuses the shared scheme; malformed ids are rejected.
    @Test
    void alarmEntryBadManagedObjectIdRejected() {
        assertThrows(CodecException.class,
                () -> codec.deserialize(fixtureWithFirstAlarm(a -> a.put("managedObjectId", "NoColon"))));
    }

    // alarms is required but an empty (still-ordered) array is a valid value.
    @Test
    void alarmsEmptyArrayAccepted() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) root.get("payload")).set("alarms", mapper.createArrayNode());
        String json = mapper.writeValueAsString(root);
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(json));
        TransactionEvent txn = (TransactionEvent) env.getPayload();
        assertTrue(txn.getAlarms().isEmpty());
    }
}
