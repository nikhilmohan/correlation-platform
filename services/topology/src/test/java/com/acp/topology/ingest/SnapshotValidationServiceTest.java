package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.topology.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC-2..AC-6 (+ AC-24 structural part): structural + semantic snapshot validation. */
class SnapshotValidationServiceTest {

    private SnapshotValidationService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotValidationService(new ObjectMapper());
    }

    @Test
    void acceptsConformingFile() {
        SnapshotFile file = service.validate(TestFixtures.snapshot("valid-all-core-ip-types.json"));
        assertThat(file.domain()).isEqualTo("core-ip");
        assertThat(file.nodes()).hasSize(11);
        assertThat(file.edges()).hasSize(10);
        assertThat(file.snapshotId()).isEqualTo("SNAP-ALL-TYPES-001");
    }

    @Test
    void rejectsMissingDomain_schemaVersion_or_nodes() {
        assertThatThrownBy(() -> service.validate(TestFixtures.snapshot("missing-domain.json")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.validate(
                "{\"domain\":\"core-ip\",\"nodes\":[],\"edges\":[]}"))
                .isInstanceOf(ValidationException.class); // missing schemaVersion
        assertThatThrownBy(() -> service.validate(
                "{\"schemaVersion\":1,\"domain\":\"core-ip\",\"edges\":[]}"))
                .isInstanceOf(ValidationException.class); // missing nodes
    }

    @Test
    void rejectsBadManagedObjectIdPattern() {
        assertThatThrownBy(() -> service.validate(TestFixtures.snapshot("bad-moid-pattern.json")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsObjectTypeNotMatchingPrefix() {
        assertThatThrownBy(() -> service.validate(TestFixtures.snapshot("objecttype-mismatch.json")))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .anyMatch(v -> v.rule().equals("objectType-prefix")));
    }

    @Test
    void rejectsDanglingEdgeReference() {
        assertThatThrownBy(() -> service.validate(TestFixtures.snapshot("dangling-edge.json")))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .anyMatch(v -> v.rule().equals("dangling-reference")));
    }
}
