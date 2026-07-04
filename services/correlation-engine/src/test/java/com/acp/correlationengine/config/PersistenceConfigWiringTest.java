package com.acp.correlationengine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.acp.correlationengine.incident.InMemoryIncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.JdbcIncidentRepository;
import com.acp.correlationengine.integration.InMemoryProcessedEventStore;
import com.acp.correlationengine.integration.JdbcProcessedEventStore;
import com.acp.correlationengine.integration.ProcessedEventStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the persistence wiring switch in {@link PersistenceConfig} so the {@code @ConditionalOnBean}
 * regression cannot recur: by default (real DB profile) the JDBC (durable) beans MUST be selected;
 * only the explicit {@code correlation.persistence.mode=memory} unit/mock path may fall back to
 * in-memory. These assertions check the active bean TYPE (not just presence), which is the
 * load-bearing guarantee the prior suite lacked — the earlier bug had the in-memory impls silently
 * winning even with a working Postgres.
 */
class PersistenceConfigWiringTest {

    /** Supplies a (mock) DataSource so the JDBC branch can build its NamedParameterJdbcTemplate. */
    @Configuration
    static class MockDataSource {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfig.class, MockDataSource.class);

    @Test
    void defaultMode_activeBeansAreJdbc() {
        // No correlation.persistence.mode set -> default jdbc -> durable JDBC beans must win.
        runner.run(context -> {
            assertThat(context).hasSingleBean(IncidentRepository.class);
            assertThat(context).hasSingleBean(ProcessedEventStore.class);
            assertThat(context.getBean(IncidentRepository.class))
                    .isInstanceOf(JdbcIncidentRepository.class);
            assertThat(context.getBean(ProcessedEventStore.class))
                    .isInstanceOf(JdbcProcessedEventStore.class);
            assertThat(context).doesNotHaveBean(InMemoryIncidentRepository.class);
            assertThat(context).doesNotHaveBean(InMemoryProcessedEventStore.class);
        });
    }

    @Test
    void explicitJdbcMode_activeBeansAreJdbc() {
        runner.withPropertyValues("correlation.persistence.mode=jdbc").run(context -> {
            assertThat(context.getBean(IncidentRepository.class))
                    .isInstanceOf(JdbcIncidentRepository.class);
            assertThat(context.getBean(ProcessedEventStore.class))
                    .isInstanceOf(JdbcProcessedEventStore.class);
        });
    }

    @Test
    void memoryMode_activeBeansAreInMemory() {
        runner.withPropertyValues("correlation.persistence.mode=memory").run(context -> {
            assertThat(context).hasSingleBean(IncidentRepository.class);
            assertThat(context).hasSingleBean(ProcessedEventStore.class);
            assertThat(context.getBean(IncidentRepository.class))
                    .isInstanceOf(InMemoryIncidentRepository.class);
            assertThat(context.getBean(ProcessedEventStore.class))
                    .isInstanceOf(InMemoryProcessedEventStore.class);
            assertThat(context).doesNotHaveBean(JdbcIncidentRepository.class);
            assertThat(context).doesNotHaveBean(JdbcProcessedEventStore.class);
        });
    }
}
