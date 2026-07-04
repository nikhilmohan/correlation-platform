package com.acp.correlationengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.JdbcIncidentRepository;
import com.acp.correlationengine.integration.JdbcProcessedEventStore;
import com.acp.correlationengine.integration.ProcessedEventStore;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the FULL Spring context against a real (Testcontainers) PostgreSQL to prove — end to end —
 * that correlation-engine is the durable incident system-of-record: with a real DataSource present,
 * the ACTIVE {@link IncidentRepository} bean is {@link JdbcIncidentRepository} and the active
 * {@link ProcessedEventStore} is {@link JdbcProcessedEventStore} (asserting the bean TYPE, not just
 * presence), and a saved incident + processed event actually land as rows in {@code incident.incident},
 * {@code incident.incident_alarm} and {@code incident.processed_event}.
 *
 * <p>This is the regression net for the {@code @ConditionalOnBean(DataSource.class)} bug where the
 * JDBC beans never activated and the in-memory impls silently won (incidents/dedupe lost on restart).
 * Tagged {@code integration}; excluded from the fast unit gate, run via the {@code integrationTest}
 * task (0 skipped — Postgres is really started).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
        "correlation-engine.kafka.enabled=false",
        "correlation-engine.integration-mode=mock",
        "correlation-engine.pattern-manager-base-url=http://localhost:0",
        "correlation-engine.codebook-generator-base-url=http://localhost:0",
        "correlation-engine.knowledge-base-url=http://localhost:0",
        "correlation-engine.knowledge-domain=core-ip",
        "correlation-engine.expiry-tick-ms=60000",
        "correlation-engine.rca-eval-mode=off",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class PersistenceWiringIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("acp").withUsername("acp").withPassword("acp");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "incident");
        registry.add("spring.flyway.default-schema", () -> "incident");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    /** No broker is present, so the Kafka producer emitters are supplied as no-ops. */
    @TestConfiguration
    static class Emitters {
        @Bean
        CorrelationResultEmitter correlationResultEmitter() {
            return incident -> { };
        }

        @Bean
        AlarmStatusEmitter alarmStatusEmitter() {
            return new AlarmStatusEmitter() {
                @Override public void fireInProgress(String a, long t) { }
                @Override public void fireCorrelated(String a, long t) { }
                @Override public void fireRevertedOpen(String a, long t) { }
            };
        }
    }

    @Autowired
    IncidentRepository incidentRepository;
    @Autowired
    ProcessedEventStore processedEventStore;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void activePersistenceBeansAreJdbcWhenDatasourcePresent() {
        assertThat(incidentRepository).isInstanceOf(JdbcIncidentRepository.class);
        assertThat(processedEventStore).isInstanceOf(JdbcProcessedEventStore.class);
    }

    @Test
    void savedIncidentAndProcessedEventLandAsRowsInPostgres() {
        Incident incident = new Incident("INC-WIRE-1", "T-WIRE", "root-w", "LOS",
                List.of("cw1", "cw2"), "PAT-W", null, 0.77,
                MatchCandidate.MatchType.PATTERN, "fp-wire-1",
                Instant.parse("2026-06-11T12:00:00Z"));

        assertThat(incidentRepository.save(incident)).isTrue();
        assertThat(processedEventStore.markIfNew("patterns.approved", "evt-wire-1")).isTrue();

        Long incidentRows = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM incident.incident WHERE incident_id = 'INC-WIRE-1'",
                Long.class);
        assertThat(incidentRows).isEqualTo(1L);

        Long memberRows = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM incident.incident_alarm WHERE incident_id = 'INC-WIRE-1'",
                Long.class);
        assertThat(memberRows).isEqualTo(3L); // 1 root_cause + 2 children

        Long processedRows = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM incident.processed_event "
                        + "WHERE dedupe_key = 'patterns.approved::evt-wire-1'",
                Long.class);
        assertThat(processedRows).isEqualTo(1L);
    }
}
