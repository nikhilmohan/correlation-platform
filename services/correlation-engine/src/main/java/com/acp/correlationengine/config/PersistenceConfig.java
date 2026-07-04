package com.acp.correlationengine.config;

import com.acp.correlationengine.incident.InMemoryIncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.JdbcIncidentRepository;
import com.acp.correlationengine.integration.InMemoryProcessedEventStore;
import com.acp.correlationengine.integration.JdbcProcessedEventStore;
import com.acp.correlationengine.integration.ProcessedEventStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Incident Store + event-dedupe persistence wiring. Correlation-engine is the durable
 * system-of-record for incidents and the processed-event ledger, so whenever a real PostgreSQL
 * DataSource is configured the JDBC implementations MUST win; the in-memory implementations exist
 * only for the no-datasource unit/mock path.
 *
 * <p>The switch is a deterministic property switch on {@code correlation.persistence.mode}, NOT
 * {@code @ConditionalOnBean(DataSource.class)}. {@code @ConditionalOnBean} on a user
 * {@code @Configuration} is evaluated while user config is processed — BEFORE Spring Boot's
 * {@code DataSourceAutoConfiguration} has registered the DataSource bean — so the condition sees
 * no DataSource and the JDBC beans are silently skipped, letting the in-memory fallbacks win even
 * with a fully working Postgres. That defeated durability (incidents/dedupe lived only in memory
 * and vanished on restart).
 *
 * <p>Instead, {@code correlation.persistence.mode} defaults to {@code jdbc} (durable-by-default:
 * the real/compose profile has a live Postgres and never overrides it), and only the unit/mock
 * profile explicitly sets it to {@code memory}. A plain property is resolved deterministically and
 * is not subject to bean-registration ordering, so JDBC reliably wins whenever a real DB is
 * configured.
 *
 * <p>The engine core stays agnostic — it depends only on the {@link IncidentRepository} /
 * {@link ProcessedEventStore} ports; only this wiring chooses the backing implementation.
 */
@Configuration
public class PersistenceConfig {

    /**
     * JDBC persistence — the default. Active unless {@code correlation.persistence.mode} is
     * explicitly {@code memory} (real/compose profile: durable Postgres system-of-record).
     */
    @Configuration
    @ConditionalOnProperty(
            name = "correlation.persistence.mode", havingValue = "jdbc", matchIfMissing = true)
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

    /**
     * In-memory fallback — active only when {@code correlation.persistence.mode=memory} is set
     * explicitly (the unit/mock path, with no datasource). A real-DB run defaults to {@code jdbc}
     * and never lands here.
     */
    @Bean
    @ConditionalOnProperty(name = "correlation.persistence.mode", havingValue = "memory")
    @ConditionalOnMissingBean(IncidentRepository.class)
    public IncidentRepository inMemoryIncidentRepository() {
        return new InMemoryIncidentRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "correlation.persistence.mode", havingValue = "memory")
    @ConditionalOnMissingBean(ProcessedEventStore.class)
    public ProcessedEventStore inMemoryProcessedEventStore() {
        return new InMemoryProcessedEventStore();
    }
}
