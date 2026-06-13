package com.acp.topology.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;

/**
 * AC-16 (DB-side guard: the change_type CHECK constraint rejects 'delete'), AC-14 (current/previous
 * bookkeeping: demote + evict). Testcontainers PostgreSQL with the Flyway schema-scoped migration;
 * skipped if Docker absent (build stays green Docker-less).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotRepositoryTest {

    private PostgreSQLContainer<?> postgres;
    private SnapshotRepository repository;

    @BeforeAll
    void startPostgres() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker unavailable — skipping PostgreSQL test");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("correlation").withUsername("correlation").withPassword("correlation");
        postgres.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("topology_meta").defaultSchema("topology_meta").createSchemas(true)
                .locations("classpath:db/migration").load().migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setSchema("topology_meta");
        repository = new SnapshotRepository(new JdbcTemplate(ds));
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void changeTypeCheckConstraintRejectsDelete() {
        SnapshotRecord delete = new SnapshotRecord("SNAP-DEL", "delete", "core-ip", 1, 1, 1,
                "current", null, Instant.now(), null, "t");
        assertThatThrownBy(() -> repository.insert(delete))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void currentAndPreviousBookkeeping() {
        repository.insert(record("SNAP-A", "current", "dom-bk"));
        // New ingest: demote A to previous, insert B as current.
        repository.demoteCurrentToPrevious("dom-bk");
        repository.insert(record("SNAP-B", "current", "dom-bk"));

        assertThat(repository.findCurrent("dom-bk")).map(SnapshotRecord::snapshotId)
                .contains("SNAP-B");
        assertThat(repository.findPreviousSnapshotId("dom-bk")).contains("SNAP-A");
        assertThat(repository.listByDomain("dom-bk")).hasSize(2);
    }

    private static SnapshotRecord record(String id, String status, String domain) {
        return new SnapshotRecord(id, "full-load", domain, 1, 2, 1, status, null, Instant.now(),
                null, "t");
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
