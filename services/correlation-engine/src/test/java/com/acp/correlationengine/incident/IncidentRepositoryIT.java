package com.acp.correlationengine.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.integration.JdbcProcessedEventStore;
import com.acp.correlationengine.integration.ProcessedEventStore;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL integration test for the Incident Store — runs Flyway on the owned {@code incident}
 * schema exactly as production ({@code spring.flyway.schemas=incident}) and round-trips the JDBC
 * repository + event-dedupe ledger against a live database (a present-but-skipped integration test is
 * zero coverage, so this actually starts Postgres via Testcontainers). Tagged {@code integration};
 * excluded from the fast unit gate, run via the {@code integrationTest} task.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("acp").withUsername("acp").withPassword("acp");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcIncidentRepository repository;
    private ProcessedEventStore processedEvents;

    @BeforeAll
    void migrate() {
        POSTGRES.start();
        DataSource ds = dataSource();
        Flyway.configure()
                .dataSource(ds)
                .schemas("incident")
                .defaultSchema("incident")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        this.jdbc = new NamedParameterJdbcTemplate(ds);
        this.repository = new JdbcIncidentRepository(jdbc);
        this.processedEvents = new JdbcProcessedEventStore(jdbc);
    }

    @AfterAll
    void stop() {
        POSTGRES.stop();
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @Test
    void flywayCreatesIncidentSchemaAndTables() {
        Long schemas = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'incident'",
                Long.class);
        assertThat(schemas).isEqualTo(1L);
        Long tables = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'incident'",
                Long.class);
        assertThat(tables).isGreaterThanOrEqualTo(3L); // incident, incident_alarm, processed_event
    }

    @Test
    void persistsAndReadsBackIncidentWithMembership() {
        Incident incident = new Incident("INC-IT-1", "T1", "root", "LOS",
                List.of("c1", "c2"), "PAT-1", null, 0.9123,
                MatchCandidate.MatchType.PATTERN, "fp-it-1", Instant.parse("2026-06-11T12:00:00Z"));
        assertThat(repository.save(incident)).isTrue();

        Incident read = repository.findById("INC-IT-1").orElseThrow();
        assertThat(read.rootCauseAlarmId()).isEqualTo("root");
        assertThat(read.rootCauseAlarmType()).isEqualTo("LOS");
        assertThat(read.childAlarmIds()).containsExactly("c1", "c2");
        assertThat(read.matchedPatternId()).isEqualTo("PAT-1");
        assertThat(read.confidence()).isEqualTo(0.9123);
    }

    @Test
    void idempotentOnFingerprint_duplicateIsNoOp() {
        Incident a = new Incident("INC-IT-2", "T1", "root", "LOS",
                List.of("c1"), "PAT-1", null, 0.8,
                MatchCandidate.MatchType.PATTERN, "fp-dup", Instant.parse("2026-06-11T12:00:00Z"));
        Incident sameFingerprint = new Incident("INC-IT-2b", "T1", "root", "LOS",
                List.of("c1"), "PAT-1", null, 0.8,
                MatchCandidate.MatchType.PATTERN, "fp-dup", Instant.parse("2026-06-11T12:00:00Z"));

        assertThat(repository.save(a)).isTrue();
        assertThat(repository.save(sameFingerprint)).isFalse(); // duplicate fingerprint -> no-op
    }

    @Test
    void statsAggregationQueriesRunAgainstRealSchema() {
        repository.save(new Incident("INC-IT-3", "T2", "r3", "PortDown", List.of("cc1", "cc2"),
                null, "CODEBOOK-1", 0.65, MatchCandidate.MatchType.CODEBOOK, "fp-3",
                Instant.parse("2026-06-11T12:00:00Z")));
        assertThat(repository.totalIncidents()).isGreaterThanOrEqualTo(1);
        assertThat(repository.countByMatchType("codebook")).isGreaterThanOrEqualTo(1);
        assertThat(repository.distinctCorrelatedAlarmCount()).isGreaterThanOrEqualTo(1);
        assertThat(repository.confidenceDistribution()).containsKey("0.6-0.8");
    }

    @Test
    void filterByTrailAndMatchType_pagesCorrectly() {
        repository.save(new Incident("INC-IT-4", "TX", "r4", "LOS", List.of("x1"),
                "PAT-9", null, 0.9, MatchCandidate.MatchType.PATTERN, "fp-4",
                Instant.parse("2026-06-11T12:00:00Z")));
        var filter = new IncidentRepository.IncidentFilter("TX", null, null, "pattern", 10, 0);
        assertThat(repository.count(filter)).isEqualTo(1);
        List<Incident> found = repository.find(filter);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).incidentId()).isEqualTo("INC-IT-4");
    }

    @Test
    void processedEventLedger_dedupesByEventId() {
        assertThat(processedEvents.markIfNew("patterns.approved", "evt-1")).isTrue();
        assertThat(processedEvents.markIfNew("patterns.approved", "evt-1")).isFalse(); // redelivered
        assertThat(processedEvents.markIfNew("codebook.generated", "evt-1")).isTrue(); // diff scope
    }

    @Test
    void insertingMembershipTwice_isIdempotent() {
        Incident incident = new Incident("INC-IT-5", "T1", "root5", "LOS", List.of("m1"),
                "PAT-1", null, 0.9, MatchCandidate.MatchType.PATTERN, "fp-5",
                Instant.parse("2026-06-11T12:00:00Z"));
        assertThat(repository.save(incident)).isTrue();
        // saving an identical-fingerprint incident again does not duplicate membership rows
        assertThat(repository.save(incident)).isFalse();
        Long members = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM incident.incident_alarm WHERE incident_id = 'INC-IT-5'",
                Long.class);
        assertThat(members).isEqualTo(2L); // root + 1 child, no duplicates
    }
}
