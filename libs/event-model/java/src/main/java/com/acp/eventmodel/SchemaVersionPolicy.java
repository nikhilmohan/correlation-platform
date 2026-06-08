package com.acp.eventmodel;

/**
 * schemaVersion compatibility policy (spec criterion 3).
 *
 * <p>The initial supported major version is {@code 1}. Consumers accept major {@code 1} and
 * reject any envelope whose major version is {@code >= 2}. {@code schemaVersion} is a plain
 * integer (its value <em>is</em> the major version); minor versions are additive and not
 * encoded separately for the MVP.
 *
 * <p>This mirrors the Python binding's {@code version.py} exactly so both bindings agree.
 */
public final class SchemaVersionPolicy {

    /** The single supported major version. */
    public static final int SUPPORTED_MAJOR = 1;

    private SchemaVersionPolicy() {
    }

    /**
     * Accept major {@code 1}; reject anything {@code >= 2} (and anything {@code < 1}).
     *
     * @param schemaVersion the envelope's {@code schemaVersion}
     * @throws SchemaVersionException if {@code schemaVersion} is not the supported major
     */
    public static void check(int schemaVersion) {
        if (schemaVersion != SUPPORTED_MAJOR) {
            throw new SchemaVersionException(
                    "unsupported schemaVersion " + schemaVersion + ": this binding supports major "
                            + SUPPORTED_MAJOR + " only (reject >= 2)");
        }
    }
}
