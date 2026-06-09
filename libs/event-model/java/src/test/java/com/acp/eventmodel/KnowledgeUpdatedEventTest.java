package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acp.eventmodel.generated.KnowledgeUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * KnowledgeUpdatedEvent (Java side): required {@code recordType}/{@code version}/{@code domain},
 * optional {@code recordId}, additionalProperties rejection, valid binding. Mirrors the Python
 * {@code test_knowledge_updated.py} against the SAME shared golden fixture.
 *
 * <p>KnowledgeUpdatedEvent is the minimal refresh trigger carried on {@code knowledge.updated}: it
 * tells consumers WHAT changed (recordType / recordId / version / domain) so they re-fetch the
 * specific version via the Knowledge API; the knowledge itself is not in the event.
 */
class KnowledgeUpdatedEventTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private String mutate(java.util.function.Consumer<ObjectNode> payloadEdit) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Fixtures.read("KnowledgeUpdatedEvent"));
            payloadEdit.accept((ObjectNode) root.get("payload"));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest(name = "missing {0} rejected")
    @ValueSource(strings = {"recordType", "version", "domain"})
    void missingRequiredFieldRejected(String field) {
        assertThrows(CodecException.class, () -> codec.deserialize(mutate(p -> p.remove(field))));
    }

    @org.junit.jupiter.api.Test
    void recordIdOptional() throws Exception {
        // recordId absent is valid (a broader change of that recordType).
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(mutate(p -> p.remove("recordId"))));
        KnowledgeUpdatedEvent ku = (KnowledgeUpdatedEvent) env.getPayload();
        assertNull(ku.getRecordId(), "recordId optional");
        // Omitted on the wire when absent (canonical NON_NULL output).
        String wire = codec.serialize(env);
        assertFalse(mapper.readTree(wire).get("payload").has("recordId"),
                "absent recordId omitted on the wire");
    }

    @org.junit.jupiter.api.Test
    void additionalPropertiesRejected() {
        assertThrows(CodecException.class,
                () -> codec.deserialize(mutate(p -> p.put("unexpected", "x"))));
    }

    @org.junit.jupiter.api.Test
    void validAccepted() {
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> codec.deserialize(Fixtures.read("KnowledgeUpdatedEvent")));
        KnowledgeUpdatedEvent ku = (KnowledgeUpdatedEvent) env.getPayload();
        assertEquals("propagationTemplate", ku.getRecordType());
        assertEquals("PROP-TMPL-CORE-IP-042", ku.getRecordId());
        assertEquals("3", ku.getVersion());
        assertEquals("core-ip", ku.getDomain());
    }
}
