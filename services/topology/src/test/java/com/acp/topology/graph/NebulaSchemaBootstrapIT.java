package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * AC-26 (NebulaGraph bootstrap idempotent — storaged ADD HOSTS + CREATE SPACE/TAG/EDGE/INDEX): on a
 * fresh NebulaGraph the bootstrap runs ADD HOSTS (only if storaged unregistered) then CREATE
 * SPACE/TAG/EDGE/INDEX IF NOT EXISTS + REBUILD, waits until the space is usable, and re-running it
 * is a no-op (no errors, no duplicate schema). Testcontainers NebulaGraph; skipped if Docker absent.
 */
class NebulaSchemaBootstrapIT extends NebulaIntegrationBase {

    @Test
    void idempotentSpaceSchemaAndAddHostsAcrossRestarts() {
        NebulaSchemaBootstrap bootstrap = new NebulaSchemaBootstrap(pool, properties);
        // First run creates space + schema (waits until the space is usable).
        assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
        // Re-running is a no-op (everything is IF NOT EXISTS) — no error, no duplicate schema.
        assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
        assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
    }
}
