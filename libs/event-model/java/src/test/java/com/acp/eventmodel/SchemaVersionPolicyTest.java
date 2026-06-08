package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Criterion 3 (Java side): unknown major {@code schemaVersion} rejected (accept 1 / reject 2).
 *
 * <p>Asserted both at the policy level and end-to-end through the codec against a real fixture, so
 * the boundary values 1 (accept) and 2 (reject) behave as the contract requires — matching the
 * Python {@code test_version.py}.
 */
class SchemaVersionPolicyTest {

    private final EventCodec codec = new EventCodec();

    @Test
    void acceptsMajor1() {
        assertDoesNotThrow(() -> SchemaVersionPolicy.check(1));
    }

    @Test
    void rejectsMajor2() {
        assertThrows(SchemaVersionException.class, () -> SchemaVersionPolicy.check(2));
    }

    @Test
    void codecAcceptsSchemaVersion1() {
        String fixture = Fixtures.read("AlarmEvent");
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> codec.deserialize(fixture));
        assertEquals(1, env.getSchemaVersion());
    }

    @Test
    void codecRejectsSchemaVersion2() {
        String fixture = Fixtures.read("AlarmEvent").replace("\"schemaVersion\": 1",
                "\"schemaVersion\": 2");
        assertThrows(SchemaVersionException.class, () -> codec.deserialize(fixture));
    }
}
