package com.acp.alarmmanager.domain;

/**
 * The Alarm Manager lifecycle STATE (distinct from the wire {@code state} which is only
 * {@code raised}/{@code cleared}). {@code reverted-open} is NOT a state — it is a transition back
 * to {@link #OPEN} distinguished by the audit reason.
 */
public enum LifecycleState {
    OPEN("open"),
    IN_PROGRESS("in-progress"),
    CORRELATED("correlated"),
    CLEARED("cleared");

    private final String wire;

    LifecycleState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Parse a wire value ({@code open}/{@code in-progress}/{@code correlated}/{@code cleared}). */
    public static LifecycleState fromWire(String value) {
        for (LifecycleState s : values()) {
            if (s.wire.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown lifecycle state: " + value);
    }
}
