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
            seq.forEach(n -> sequence.add(n.asText()));
        }

        JsonNode sw = view.get("sessionWindow");
        long windowMs = sw != null && sw.has("windowMs") ? sw.get("windowMs").asLong() : 0L;
        WindowType windowType = WindowType.fromWire(sw != null && sw.has("type")
                ? sw.get("type").asText() : "gap-based");

        return new PatternRef(patternId, trailId, sequence, rootCauseAlarmType, confidence,
                windowMs, windowType);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
