package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Criterion 5 (Java side): {@code type} discriminates to exactly one payload; unknown {@code type}
 * rejected. Mirrors the Python {@code test_registry.py}.
 */
class TypeRegistryTest {

    private final EventCodec codec = new EventCodec();

    static java.util.stream.Stream<String> payloadTypes() {
        return Fixtures.ALL_TYPES.stream();
    }

    @ParameterizedTest(name = "resolves to its payload class: {0}")
    @MethodSource("payloadTypes")
    void resolves(String type) {
        Class<?> resolved = TypeRegistry.resolve(type);
        assertEquals("com.acp.eventmodel.generated." + type, resolved.getName(),
                "each type resolves to exactly its generated payload class");
        // End-to-end: the codec binds the same class.
        assertEquals(resolved, codec.deserialize(Fixtures.read(type)).getPayload().getClass());
    }

    @Test
    void allElevenTypesRegistered() {
        assertEquals(11, TypeRegistry.knownTypes().size());
    }

    @Test
    void unknownTypeRejected() {
        assertThrows(UnknownEventTypeException.class, () -> TypeRegistry.resolve("FooEvent"));
    }

    @Test
    void unknownTypeInEnvelopeRejected() {
        // `type` not in the envelope enum is rejected at envelope schema validation; an
        // out-of-contract value never resolves to a payload class.
        String fixture = Fixtures.read("AlarmEvent").replace("\"type\": \"AlarmEvent\"",
                "\"type\": \"FooEvent\"");
        assertThrows(CodecException.class, () -> codec.deserialize(fixture));
    }
}
