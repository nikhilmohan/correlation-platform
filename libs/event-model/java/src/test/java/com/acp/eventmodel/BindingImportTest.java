package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acp.eventmodel.generated.AlarmEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Criterion 17 (Java side): the binding is importable and usable as a dependency.
 *
 * <p>The authoritative proof of criterion 17 is the clean {@code ./gradlew build} producing the
 * jar (run in CI). This test additionally exercises the public API end-to-end the way a downstream
 * Spring service would: build a payload POJO from scratch, wrap it in a {@link TypedEnvelope},
 * serialize to canonical wire JSON, and deserialize it back — proving the generated models, codec,
 * registry, and {@code managedObjectId} helper are all importable and wired together.
 */
class BindingImportTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publicApiIsUsableFromScratch() throws Exception {
        // Construct a valid managedObjectId via the value type.
        ManagedObjectId moi = ManagedObjectId.parse("Port:PE1-LC2-P3");

        // Build a payload using the generated builder API a downstream service would import.
        AlarmEvent alarm = new AlarmEvent()
                .withAlarmId("ALM-9999")
                .withManagedObjectId(moi.toString())
                .withEventType("communicationsAlarm")
                .withProbableCause("lossOfSignal")
                .withAlarmType("LinkDown")
                .withPerceivedSeverity("critical")
                .withRaisedAt("2026-06-08T12:34:55Z")
                .withState(AlarmEvent.State.RAISED)
                .withTrailIds(List.of());

        TypedEnvelope<AlarmEvent> env = new TypedEnvelope<>(
                "11111111-1111-4111-8111-111111111111", "AlarmEvent", 1,
                "2026-06-08T12:34:56Z", "simulator", "trace-import-0001", alarm);

        String wire = codec.serialize(env);
        assertNotNull(wire);

        TypedEnvelope<Object> back = codec.deserialize(wire);
        assertEquals("AlarmEvent", back.getType());
        assertEquals(AlarmEvent.class, back.getPayload().getClass());

        // Empty trailIds emitted as [] (canonical wire format), optionals omitted.
        JsonNode tree = mapper.readTree(wire);
        assertTrue(tree.get("payload").get("trailIds").isArray());
        assertEquals(0, tree.get("payload").get("trailIds").size());
        assertTrue(tree.get("payload").get("clearedAt") == null, "absent clearedAt omitted");
        assertTrue(tree.get("payload").get("vendorRaw") == null, "absent vendorRaw omitted");
    }
}
