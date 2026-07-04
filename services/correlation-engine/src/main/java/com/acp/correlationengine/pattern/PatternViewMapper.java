package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the Pattern Manager {@code PatternPage} envelope ({@code {items[], total, limit, offset}})
 * of {@code PatternView} items into {@link PatternRef}s. The {@code trailId} is read off
 * {@code PatternView.trailId} (never off any event). Reused by the real client + the unit-test mock
 * so both interpret the same published shape identically.
 */
public final class PatternViewMapper {

    private PatternViewMapper() {
    }

    /** @return the approved patterns from a {@code PatternPage} envelope node. */
    public static List<PatternRef> fromPage(JsonNode page) {
        List<PatternRef> out = new ArrayList<>();
        if (page == null) {
            return out;
        }
        JsonNode items = page.get("items");
        if (items == null || !items.isArray()) {
            return out;
        }
        for (JsonNode view : items) {
            out.add(fromView(view));
        }
        return out;
    }

    /** @return one {@link PatternRef} from a single {@code PatternView} node. */
    public static PatternRef fromView(JsonNode view) {
        String patternId = text(view, "patternId");
        String trailId = text(view, "trailId");
        String rootCauseAlarmType = text(view, "rootCauseAlarmType");
        double confidence = view.has("confidence") ? view.get("confidence").asDouble() : 0.0;

        List<String> sequence = new ArrayList<>();
        JsonNode seq = view.get("sequence");
        if (seq != null && seq.isArray()) {
            for (JsonNode n : seq) {
                String alarmType = sequenceElementAlarmType(n);
                if (alarmType != null && !alarmType.isEmpty()) {
                    sequence.add(alarmType);
                }
            }
        }

        JsonNode sw = view.get("sessionWindow");
        long windowMs = sw != null && sw.has("windowMs") ? sw.get("windowMs").asLong() : 0L;
        WindowType windowType = WindowType.fromWire(sw != null && sw.has("type")
                ? sw.get("type").asText() : "gap-based");

        return new PatternRef(patternId, trailId, sequence, rootCauseAlarmType, confidence,
                windowMs, windowType);
    }

    /**
     * Extract the alarmType token from one {@code sequence} element. The real Pattern Manager
     * {@code PatternView.sequence} is an array of {@code SequenceElementView} OBJECTS
     * ({@code {"alarmType": ..., "optional": ...}}), so we read {@code element.alarmType}. A bare
     * string element (legacy shape) is still accepted for defensiveness. The engine's sequence model
     * is a {@code List<String>} of alarmType tokens; optionality is applied via the Knowledge
     * {@code partialMatchTolerance} at full-match evaluation, so the per-element {@code optional}
     * flag is not carried into {@link PatternRef}.
     *
     * @return the alarmType token, or {@code null} if the element carries none.
     */
    private static String sequenceElementAlarmType(JsonNode element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (element.isTextual()) {
            return element.asText(); // legacy bare-string element — still handled
        }
        JsonNode alarmType = element.get("alarmType");
        return alarmType == null || alarmType.isNull() ? null : alarmType.asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
