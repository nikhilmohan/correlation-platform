package com.acp.correlationengine.config;

import com.acp.correlationengine.incident.InMemoryIncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.JdbcIncidentRepository;
import com.acp.correlationengine.integration.InMemoryProcessedEventStore;
import com.acp.correlationengine.integration.JdbcProcessedEventStore;
import com.acp.correlationengine.integration.ProcessedEventStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Incident Store + event-dedupe persistence wiring. When a {@link DataSource} is present (real
 * PostgreSQL via Flyway on the owned {@code incident} schema) the JDBC implementations are used;
 * otherwise the in-memory implementations back the mock/unit profile. The engine core is agnostic —
 * it depends only on the {@link IncidentRepository} / {@link ProcessedEventStore} ports.
 */
@Configuration
public class PersistenceConfig {

    /** JDBC persistence — active whenever a {@link DataSource} is on the context (real Postgres). */
    @Configuration
    @ConditionalOnBean(DataSource.class)
    static class Jdbc {
        @Bean
        @ConditionalOnMissingBean(NamedParameterJdbcTemplate.class)
        public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        @ConditionalOnMissingBean(IncidentRepository.class)
        public IncidentRepository incidentRepository(NamedParameterJdbcTemplate jdbc) {
            return new JdbcIncidentRepository(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(ProcessedEventStore.class)
        public ProcessedEventStore processedEventStore(NamedParameterJdbcTemplate jdbc) {
            return new JdbcProcessedEventStore(jdbc);
        }
    }

    @Bean
    @ConditionalOnMissingBean(IncidentRepository.class)
    public IncidentRepository inMemoryIncidentRepository() {
        return new InMemoryIncidentRepository();
    }

    @Bean
    @ConditionalOnMissingBean(ProcessedEventStore.class)
    public ProcessedEventStore inMemoryProcessedEventStore() {
        return new InMemoryProcessedEventStore();
    }
}
