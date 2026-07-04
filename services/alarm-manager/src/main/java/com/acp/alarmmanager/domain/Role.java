package com.acp.alarmmanager.domain;

/**
 * The correlation-group ROLE, written only by the ROLE channel ({@code CorrelationResultEvent}).
 * Default {@link #NONE} until a correlation result assigns {@link #ROOT_CAUSE} or {@link #CHILD}.
 */
public enum Role {
    ROOT_CAUSE("root-cause"),
    CHILD("child"),
    NONE("none");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Role fromWire(String value) {
        for (Role r : values()) {
            if (r.wire.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown role: " + value);
    }
}
