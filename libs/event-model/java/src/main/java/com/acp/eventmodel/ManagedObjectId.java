package com.acp.eventmodel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code managedObjectId} value type and validation (spec criteria 7, 15, 16).
 *
 * <p>The {@code managedObjectId} is the shared identity binding between alarms (Simulator) and the
 * topology graph (Topology Service). Wire format is a single string {@code "<objectType>:<id>"}
 * where {@code objectType} is one of the nine known typed graph layers and {@code id} is a stable,
 * non-empty string containing no colon.
 *
 * <p>The validation rule here mirrors the JSON Schema {@code pattern} in
 * {@code schema/common/managedObjectId.schema.json} (the schema is the source of truth); this
 * class is a thin, schema-agnostic helper — it references the known type names, not any payload
 * field list — and is the exact Java counterpart of the Python {@code managed_object_id.py}.
 */
public final class ManagedObjectId {

    /** The nine known typed graph layers (Solution Design §5). Frozen contract. */
    public static final List<String> KNOWN_OBJECT_TYPES = List.of(
            "Node", "LineCard", "Port", "IPLink", "IGPAdjacency", "LSP", "VPNService", "FiberSpan",
            "SRLG");

    /** Wire-format pattern: {@code <knownObjectType>:<non-empty id with no colon>}. */
    public static final Pattern PATTERN =
            Pattern.compile("^(" + String.join("|", KNOWN_OBJECT_TYPES) + "):[^:]+$");

    private final String objectType;
    private final String id;

    private ManagedObjectId(String objectType, String id) {
        this.objectType = objectType;
        this.id = id;
    }

    /**
     * Parse and validate a wire string into a {@link ManagedObjectId}.
     *
     * @param value the wire string {@code "<objectType>:<id>"}
     * @return the parsed value object
     * @throws ManagedObjectIdException if {@code value} is not well-formed
     */
    public static ManagedObjectId parse(String value) {
        validate(value);
        int colon = value.indexOf(':');
        return new ManagedObjectId(value.substring(0, colon), value.substring(colon + 1));
    }

    /** @return {@code true} iff {@code value} is a well-formed {@code managedObjectId} string. */
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /**
     * Validate a {@code managedObjectId} string, returning it on success.
     *
     * @param value the candidate string
     * @return {@code value} unchanged on success
     * @throws ManagedObjectIdException if {@code value} is not of the form
     *     {@code <knownObjectType>:<non-empty-id>}
     */
    public static String validate(String value) {
        if (!isValid(value)) {
            throw new ManagedObjectIdException(
                    "invalid managedObjectId " + (value == null ? "null" : "'" + value + "'")
                            + ": expected '<objectType>:<id>' with objectType in "
                            + KNOWN_OBJECT_TYPES + " and a non-empty id containing no colon");
        }
        return value;
    }

    public String getObjectType() {
        return objectType;
    }

    public String getId() {
        return id;
    }

    /** @return the canonical wire form {@code "<objectType>:<id>"}. */
    @Override
    public String toString() {
        return objectType + ":" + id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ManagedObjectId other)) {
            return false;
        }
        return objectType.equals(other.objectType) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return 31 * objectType.hashCode() + id.hashCode();
    }
}
