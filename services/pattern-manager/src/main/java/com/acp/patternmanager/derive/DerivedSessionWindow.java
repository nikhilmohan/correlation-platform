package com.acp.patternmanager.derive;

/**
 * The derived per-pattern session-window rule ({@code windowMs} + {@code type}). Distinct from the
 * generated event-model {@code SessionWindow} POJO (this is the internal derivation result); it is
 * mapped onto the event POJO at emit time and persisted on the pattern record.
 *
 * @param windowMs session-window duration in ms (always {@code > 0})
 * @param type window semantics ({@code gap-based} or {@code fixed})
 */
public record DerivedSessionWindow(long windowMs, WindowType type) {

    /** Window semantics — the two values allowed by {@code common/sessionWindow.schema.json}. */
    public enum WindowType {
        GAP_BASED("gap-based"),
        FIXED("fixed");

        private final String wire;

        WindowType(String wire) {
            this.wire = wire;
        }

        /** @return the canonical wire token ({@code gap-based} / {@code fixed}). */
        public String wire() {
            return wire;
        }
    }
}
