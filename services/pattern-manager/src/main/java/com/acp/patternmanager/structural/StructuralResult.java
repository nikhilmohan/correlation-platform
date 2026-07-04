package com.acp.patternmanager.structural;

/**
 * Structural-validation outcome. Internal to the Pattern Store + read API — deliberately NOT carried
 * on the frozen events.
 *
 * @param structurallyValidated whether the resolved objects form a connected dependency path
 * @param reason a non-null reason naming the disconnected object(s) when {@code structurallyValidated}
 *     is false; null when true
 */
public record StructuralResult(boolean structurallyValidated, String reason) {

    /** @return a passing result (connected). */
    public static StructuralResult pass() {
        return new StructuralResult(true, null);
    }

    /**
     * @param reason why the objects are not connected
     * @return a flagged (failing) result
     */
    public static StructuralResult flag(String reason) {
        return new StructuralResult(false, reason);
    }
}
