package com.acp.correlationengine.model;

/**
 * Per-pattern session-window semantics, sourced from the pattern's {@code sessionWindow.type}.
 *
 * <ul>
 *   <li>{@link #GAP_BASED} — the window resets/extends on each new matching alarm; an idle gap of
 *       {@code windowMs} closes it.</li>
 *   <li>{@link #FIXED} — a fixed {@code windowMs} from instance start.</li>
 * </ul>
 */
public enum WindowType {
    GAP_BASED,
    FIXED;

    /** @return the {@link WindowType} for the wire token ({@code gap-based} / {@code fixed}). */
    public static WindowType fromWire(String token) {
        if (token == null) {
            return GAP_BASED;
        }
        return switch (token) {
            case "fixed" -> FIXED;
            case "gap-based" -> GAP_BASED;
            default -> throw new IllegalArgumentException("unknown sessionWindow type: " + token);
        };
    }
}
