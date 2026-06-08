package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criterion 6 (Java side): all seven required envelope fields enforced. Omitting any of
 * {@code eventId, type, schemaVersion, occurredAt, source, traceId, payload} raises. Mirrors the
 * Python {@code test_envelope_required.py}.
 */
class EnvelopeRequiredFieldsTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "missing envelope field rejected: {0}")
    @ValueSource(strings = {"eventId", "type", "schemaVersion", "occurredAt", "source", "traceId",
            "payload"})
    void missingFieldRejected(String field) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(Fixtures.read("AlarmEvent"));
        root.remove(field);
        String json = mapper.writeValueAsString(root);

        assertThrows(CodecException.class, () -> codec.deserialize(json),
                "omitting required envelope field '" + field + "' must raise");
    }
}
