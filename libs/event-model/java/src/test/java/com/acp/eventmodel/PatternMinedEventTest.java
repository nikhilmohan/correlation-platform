package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acp.eventmodel.generated.PatternMinedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Criteria 10, 11 (Java side): PatternMinedEvent carries no RCA/lifecycle/patternId, and
 * {@code provenance} is a required nested object with required sub-fields while {@code trailId} is
 * a required top-level field. Mirrors the Python {@code test_pattern_mined.py}.
 */
class PatternMinedEventTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private String mutate(java.util.function.Consumer<ObjectNode> payloadEdit) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Fixtures.read("PatternMinedEvent"));
            payloadEdit.accept((ObjectNode) root.get("payload"));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Criterion 10 — no rootCauseAlarmType / lifecycle / patternId.
    @Test
    void noRcaLifecyclePatternIdFields() {
        // The generated POJO defines none of these fields.
        java.util.Set<String> fieldNames = Arrays.stream(PatternMinedEvent.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(fieldNames.contains("rootCauseAlarmType"));
        assertFalse(fieldNames.contains("lifecycle"));
        assertFalse(fieldNames.contains("patternId"));

        // Such input fields are rejected (additionalProperties:false in the schema).
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("rootCauseAlarmType", "lossOfSignal"))));
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("lifecycle", "draft"))));
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("patternId", "PAT-0001"))));

        // And they never appear in serialized output.
        TypedEnvelope<Object> env = codec.deserialize(Fixtures.read("PatternMinedEvent"));
        String wire = codec.serialize(env);
        assertFalse(wire.contains("rootCauseAlarmType"));
        assertFalse(wire.contains("lifecycle"));
        assertFalse(wire.contains("patternId"));
    }

    // Criterion 11 — provenance required; sub-fields required; trailId top-level required.
    @Test
    void provenanceRequired() {
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p -> p.remove("provenance"))));
    }

    @Test
    void provenanceSubFieldsRequired() {
        for (String sub : new String[] {"sourceWindowId", "snapshotId", "codebookVersion"}) {
            assertThrows(CodecException.class,
                    () -> codec.deserialize(mutate(p -> ((ObjectNode) p.get("provenance")).remove(sub))),
                    "missing provenance." + sub + " must raise");
        }
    }

    @Test
    void trailIdTopLevelRequired() {
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p -> p.remove("trailId"))));
    }

    @Test
    void validPatternMinedAccepted() {
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("PatternMinedEvent")));
        PatternMinedEvent mined = (PatternMinedEvent) env.getPayload();
        assertEquals("TRAIL-0001", mined.getTrailId());
        assertEquals("TXN-0001", mined.getProvenance().getSourceWindowId());
    }

    // Optional provenance.anchorScenarioId — present + absent (backward-compat), mirroring `domain`.

    @Test
    void anchorScenarioIdPresentRoundTrips() {
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("PatternMinedEvent")));
        PatternMinedEvent mined = (PatternMinedEvent) env.getPayload();
        assertEquals(
                "CODEBOOK-2026-06-08-001:FiberSpan:F-N0_N1",
                mined.getProvenance().getAnchorScenarioId());
        String wire = codec.serialize(env);
        assertTrue(wire.contains("CODEBOOK-2026-06-08-001:FiberSpan:F-N0_N1"));
    }

    @Test
    void anchorScenarioIdAbsentIsOptional() {
        // Backward-compat: a provenance WITHOUT anchorScenarioId still deserializes (null =
        // "unexplained" cascade, a first-class outcome). anchorScenarioId is not in
        // provenance.required, so removing it must NOT raise.
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(
                mutate(p -> ((ObjectNode) p.get("provenance")).remove("anchorScenarioId"))));
        PatternMinedEvent mined = (PatternMinedEvent) env.getPayload();
        assertNull(mined.getProvenance().getAnchorScenarioId());
        // And it does not leak into the wire when absent (NON_NULL inclusion).
        String wire = codec.serialize(env);
        assertFalse(wire.contains("anchorScenarioId"));
    }

    // Optional top-level sampleAlarms[] — a bounded sample of the real member alarms a pattern
    // was mined from (operator review / XAI). Present + absent (backward-compat).

    @Test
    void sampleAlarmsPresentRoundTrips() {
        // (a) The fixture carries a 2-entry sample; all 5 fields of each item survive.
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("PatternMinedEvent")));
        PatternMinedEvent mined = (PatternMinedEvent) env.getPayload();
        assertEquals(2, mined.getSampleAlarms().size());
        var first = mined.getSampleAlarms().get(0);
        assertEquals("ALM-0001262", first.getAlarmId());
        assertEquals("lossOfSignal", first.getAlarmType());
        assertEquals("2026-06-08T12:38:51Z", first.getRaisedAt());
        assertEquals("IPLink:N6_N7", first.getManagedObjectId());
        assertEquals("major", first.getPerceivedSeverity());
        String wire = codec.serialize(env);
        assertTrue(wire.contains("ALM-0001262"));
        assertTrue(wire.contains("IPLink:N6_N7"));
    }

    @Test
    void sampleAlarmsAbsentIsOptional() {
        // (b) Backward-compat: a PatternMinedEvent WITHOUT sampleAlarms still deserializes.
        // sampleAlarms is not in the payload's required list, so removing it must NOT raise.
        TypedEnvelope<Object> env = assertDoesNotThrow(
                () -> codec.deserialize(mutate(p -> p.remove("sampleAlarms"))));
        PatternMinedEvent mined = (PatternMinedEvent) env.getPayload();
        assertNull(mined.getSampleAlarms());
        // And it does not leak into the wire when absent (NON_NULL inclusion).
        String wire = codec.serialize(env);
        assertFalse(wire.contains("sampleAlarms"));
    }

    @Test
    void sampleAlarmItemFieldsRequired() {
        // (c) A present-but-incomplete sample alarm is rejected (5 fields required within the item);
        // an unknown field inside an item is rejected (additionalProperties:false).
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p ->
                ((ObjectNode) p.get("sampleAlarms").get(0)).remove("perceivedSeverity"))));
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p ->
                ((ObjectNode) p.get("sampleAlarms").get(0)).put("bogus", "x"))));
    }
}
