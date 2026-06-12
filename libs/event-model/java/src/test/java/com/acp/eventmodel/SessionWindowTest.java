package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acp.eventmodel.generated.PatternApprovedEvent;
import com.acp.eventmodel.generated.PatternDiscoveredEvent;
import com.acp.eventmodel.generated.SessionWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Additive contract change (Java side): the shared {@code sessionWindow} object on both
 * {@code PatternDiscoveredEvent} and {@code PatternApprovedEvent}. The Pattern Manager populates
 * this authored operational directive (distinct from the descriptive {@code timing} statistics) so
 * the Correlation Engine can govern each correlation instance's lifetime.
 *
 * <p>Both events bind the SAME generated {@link SessionWindow} type (the schema {@code $ref}s the
 * shared {@code common/sessionWindow.schema.json}), mirroring the Python single-{@code SessionWindow}
 * approach (like {@code common/managedObjectId.schema.json}). The {@code type} enum carries
 * hyphenated wire values ({@code gap-based} / {@code fixed}) preserved verbatim via Jackson
 * {@code @JsonValue}/{@code @JsonCreator}. Required sub-fields, enum membership, and the
 * {@code sessionWindow}-required rule on each event are enforced by the codec against the SAME
 * {@code ../schema} files the Python binding uses. Mirrors the Python
 * {@code test_pattern_session_window.py}.
 */
class SessionWindowTest {

    private static final EventCodec CODEC = new EventCodec();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String mutate(String type, Consumer<ObjectNode> payloadEdit) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.read(type));
            payloadEdit.accept((ObjectNode) root.get("payload"));
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Typed round-trip on PatternDiscoveredEvent --------------------------------------------
    @Test
    void discoveredRoundTripsSessionWindow() {
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read("PatternDiscoveredEvent")));
        PatternDiscoveredEvent payload = (PatternDiscoveredEvent) env.getPayload();

        SessionWindow sw = payload.getSessionWindow();
        assertEquals(Integer.valueOf(60000), sw.getWindowMs(), "windowMs intact");
        assertEquals(SessionWindow.Type.GAP_BASED, sw.getType(), "type enum bound");
        // The enum re-serializes to its EXACT hyphenated wire value.
        assertEquals("gap-based", sw.getType().value(), "type preserves hyphenated wire value");

        // Re-serialize and confirm the wire still carries sessionWindow losslessly.
        String wire = CODEC.serialize(env);
        assertEquals("gap-based", readSessionWindowType(wire), "re-serialized type wire value");
        assertEquals(60000, readSessionWindowWindowMs(wire), "re-serialized windowMs");
    }

    // --- Typed round-trip on PatternApprovedEvent ----------------------------------------------
    @Test
    void approvedRoundTripsSessionWindow() {
        TypedEnvelope<Object> env =
                assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read("PatternApprovedEvent")));
        PatternApprovedEvent payload = (PatternApprovedEvent) env.getPayload();

        SessionWindow sw = payload.getSessionWindow();
        assertEquals(Integer.valueOf(60000), sw.getWindowMs(), "windowMs intact");
        assertEquals(SessionWindow.Type.GAP_BASED, sw.getType(), "type enum bound");
        assertEquals("gap-based", sw.getType().value(), "type preserves hyphenated wire value");

        String wire = CODEC.serialize(env);
        assertEquals("gap-based", readSessionWindowType(wire), "re-serialized type wire value");
        assertEquals(60000, readSessionWindowWindowMs(wire), "re-serialized windowMs");
    }

    // --- Both events share the SAME generated SessionWindow type -------------------------------
    @Test
    void bothEventsBindTheSameSessionWindowType() throws Exception {
        assertEquals(SessionWindow.class,
                PatternDiscoveredEvent.class.getDeclaredField("sessionWindow").getType(),
                "PatternDiscoveredEvent.sessionWindow uses the shared SessionWindow type");
        assertEquals(SessionWindow.class,
                PatternApprovedEvent.class.getDeclaredField("sessionWindow").getType(),
                "PatternApprovedEvent.sessionWindow uses the shared SessionWindow type");
    }

    // --- Both hyphenated enum values round-trip verbatim on both events ------------------------
    @ParameterizedTest(name = "type={0} round-trips on {1}")
    @CsvSource({
            "gap-based,PatternDiscoveredEvent",
            "fixed,PatternDiscoveredEvent",
            "gap-based,PatternApprovedEvent",
            "fixed,PatternApprovedEvent"})
    void enumWireValueRoundTrips(String wireValue, String type) {
        String json = mutate(type, p -> ((ObjectNode) p.get("sessionWindow")).put("type", wireValue));
        TypedEnvelope<Object> env = assertDoesNotThrow(() -> CODEC.deserialize(json));
        assertEquals(wireValue, readSessionWindowType(CODEC.serialize(env)),
                "wire value preserved exactly");
    }

    // --- The `type` enum rejects an invalid value ----------------------------------------------
    @ParameterizedTest(name = "invalid type rejected on {0}")
    @ValueSource(strings = {"PatternDiscoveredEvent", "PatternApprovedEvent"})
    void invalidEnumValueRejected(String type) {
        assertThrows(CodecException.class, () -> CODEC.deserialize(
                mutate(type, p -> ((ObjectNode) p.get("sessionWindow")).put("type", "sliding"))));
    }

    // --- windowMs + type are required sub-fields (on both events) ------------------------------
    @ParameterizedTest(name = "missing sessionWindow.{0} rejected on PatternDiscoveredEvent")
    @ValueSource(strings = {"windowMs", "type"})
    void discoveredSessionWindowSubFieldsRequired(String sub) {
        assertThrows(CodecException.class, () -> CODEC.deserialize(mutate("PatternDiscoveredEvent",
                p -> ((ObjectNode) p.get("sessionWindow")).remove(sub))));
    }

    @ParameterizedTest(name = "missing sessionWindow.{0} rejected on PatternApprovedEvent")
    @ValueSource(strings = {"windowMs", "type"})
    void approvedSessionWindowSubFieldsRequired(String sub) {
        assertThrows(CodecException.class, () -> CODEC.deserialize(mutate("PatternApprovedEvent",
                p -> ((ObjectNode) p.get("sessionWindow")).remove(sub))));
    }

    // --- sessionWindow is required on BOTH events ----------------------------------------------
    @ParameterizedTest(name = "missing sessionWindow rejected on {0}")
    @ValueSource(strings = {"PatternDiscoveredEvent", "PatternApprovedEvent"})
    void sessionWindowRequiredOnBothEvents(String type) {
        assertThrows(CodecException.class,
                () -> CODEC.deserialize(mutate(type, p -> p.remove("sessionWindow"))));
    }

    @ParameterizedTest(name = "full event accepted: {0}")
    @ValueSource(strings = {"PatternDiscoveredEvent", "PatternApprovedEvent"})
    void fullEventAccepted(String type) {
        assertDoesNotThrow(() -> CODEC.deserialize(Fixtures.read(type)));
    }

    // --- helpers --------------------------------------------------------------------------------
    private static String readSessionWindowType(String wire) {
        try {
            return MAPPER.readTree(wire).get("payload").get("sessionWindow").get("type").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int readSessionWindowWindowMs(String wire) {
        try {
            return MAPPER.readTree(wire).get("payload").get("sessionWindow").get("windowMs").asInt();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
