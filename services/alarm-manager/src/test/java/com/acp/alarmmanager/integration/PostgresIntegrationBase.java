package com.acp.alarmmanager.integration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Testcontainers PostgreSQL base for repository/Flyway integration tests. Boots a real
 * PostgreSQL, runs Flyway scoped to the {@code live_alarm} schema (exactly as the service does),
 * and exposes a {@link DataSource}. Tagged {@code integration} so it is excluded from the fast
 * unit run and executed via the {@code integrationTest} Gradle task (the live gate for the
 * raw-JDBC repositories).
 */
@Tag("integration")
@Testcontainers
public abstract class PostgresIntegrationBase {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("correlation")
                    .withUsername("correlation")
                    .withPassword("correlation");

    protected DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    protected void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .schemas("live_alarm")
                .defaultSchema("live_alarm")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
