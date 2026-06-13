package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.topology.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * AC-24 / AC-31 (single canonical snapshot-file schema, P1-G2): a conforming file validates against
 * the single checked-in schema and a malformed one fails; the canonical schema exists at exactly the
 * one path {@code services/topology/schema/snapshot.schema.json}; there is NO independent
 * {@code services/simulator/schema/...} copy (producer and validator cannot diverge).
 */
class SnapshotSchemaCanonicalTest {

    private final SnapshotValidationService validation =
            new SnapshotValidationService(new ObjectMapper());

    @Test
    void validatesAgainstSingleCheckedInSchema() {
        // A conforming file validates against the one canonical schema (loaded by the validator).
        assertThat(validation.validate(TestFixtures.snapshot("valid-min.json")).domain())
                .isEqualTo("core-ip");
        // A malformed one (missing the required top-level domain) fails.
        assertThatThrownBy(() -> validation.validate(TestFixtures.snapshot("missing-domain.json")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void exactlyOneCanonicalSchemaFileExists() {
        // The single canonical home is services/topology/schema/snapshot.schema.json.
        Path canonical = repoRoot().resolve("services/topology/schema/snapshot.schema.json");
        assertThat(Files.isRegularFile(canonical))
                .as("canonical schema present at %s", canonical).isTrue();

        // There must be NO independent Simulator copy (P1-G2 single-source guard).
        Path simulatorCopy = repoRoot().resolve("services/simulator/schema/snapshot.schema.json");
        assertThat(Files.exists(simulatorCopy))
                .as("no forked simulator schema copy at %s", simulatorCopy).isFalse();
    }

    /** Resolve the repo root from the topology project working directory (services/topology). */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        // Walk up until we find the services/ directory (robust to where Gradle runs the test from).
        Path p = cwd;
        while (p != null && !Files.isDirectory(p.resolve("services").resolve("topology"))) {
            p = p.getParent();
        }
        return p != null ? p : cwd.getParent().getParent();
    }
}
