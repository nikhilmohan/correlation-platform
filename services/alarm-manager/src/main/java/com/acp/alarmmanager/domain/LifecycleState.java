package com.acp.alarmmanager.domain;

/**
 * The Alarm Manager lifecycle STATE (distinct from the wire {@code state} which is only
 * {@code raised}/{@code cleared}). {@code reverted-open} is NOT a state — it is a transition back
 * to {@link #OPEN} distinguished by the audit reason.
 */
public enum LifecycleState {
    OPEN("open", 0),
    IN_PROGRESS("in-progress", 1),
    CORRELATED("correlated", 2),
    // cleared is a legitimate terminal that can follow correlated; it is NOT part of the
    // {open,in-progress,correlated} status-sync downgrade ordering and is intentionally ranked
    // highest so the precedence guard never blocks a real clear (see LifecycleService.applyState).
    CLEARED("cleared", 3);

    private final String wire;

    /**
     * STATUS-SYNC precedence rank (weakest→strongest): {@code open} &lt; {@code in-progress} &lt;
     * {@code correlated}. Used by the state-precedence guard so a lagging sibling pattern-instance's
     * status event can never downgrade a {@code correlated} (placed in a fired incident) alarm back
     * to {@code in-progress}/{@code open}. Higher rank = stronger.
     */
    private final int statusRank;

    LifecycleState(String wire, int statusRank) {
        this.wire = wire;
        this.statusRank = statusRank;
    }

    public String wire() {
        return wire;
    }

    /** The STATUS-SYNC precedence rank (higher = stronger). See {@link #statusRank}. */
    public int statusRank() {
        return statusRank;
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
