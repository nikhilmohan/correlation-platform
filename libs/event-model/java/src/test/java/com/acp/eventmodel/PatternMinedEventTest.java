package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
