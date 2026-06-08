package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criteria 12, 13, 14 (Java side): required-field enforcement for TrailsBuiltEvent (3),
 * CodebookGeneratedEvent (3), and TransactionEvent (6). Omitting any required payload field raises;
 * the full event is accepted. Mirrors the Python {@code test_trails_built.py},
 * {@code test_codebook_generated.py}, {@code test_transaction.py}.
 */
class RequiredFieldEventsTest {

    private static final EventCodec CODEC = new EventCodec();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String without(String type, String payloadField) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.read(type));
            ((ObjectNode) root.get("payload")).remove(payloadField);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class TrailsBuiltEventCriterion12 {
        @ParameterizedTest(name = "missing {0} rejected")
        @ValueSource(strings = {"snapshotId", "trailIds", "trailCount"})
        void missingFieldRejected(String field) {
            assertThrows(CodecException.class,
                    () -> CODEC.deserialize(without("TrailsBuiltEvent", field)));
        }

        @org.junit.jupiter.api.Test
        void validAccepted() {
            assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read("TrailsBuiltEvent")));
        }
    }

    @Nested
    class CodebookGeneratedEventCriterion13 {
        @ParameterizedTest(name = "missing {0} rejected")
        @ValueSource(strings = {"snapshotId", "scenarioCount", "codebookId"})
        void missingFieldRejected(String field) {
            assertThrows(CodecException.class,
                    () -> CODEC.deserialize(without("CodebookGeneratedEvent", field)));
        }

        @org.junit.jupiter.api.Test
        void validAccepted() {
            assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read("CodebookGeneratedEvent")));
        }
    }

    @Nested
    class TransactionEventCriterion14 {
        @ParameterizedTest(name = "missing {0} rejected")
        @ValueSource(strings = {"transactionId", "trailId", "snapshotId", "alarmIds", "windowStart",
                "windowEnd"})
        void missingFieldRejected(String field) {
            assertThrows(CodecException.class,
                    () -> CODEC.deserialize(without("TransactionEvent", field)));
        }

        @org.junit.jupiter.api.Test
        void validAccepted() {
            assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read("TransactionEvent")));
        }
    }
}
