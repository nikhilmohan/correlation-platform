package com.acp.correlationengine;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Correlation Engine — the real-time correlation core + system of record for incidents (P3).
 *
 * <p>Consumes {@code alarms.persisted.live} / {@code patterns.approved} / {@code codebook.generated};
 * produces {@code correlation.results} + {@code alarms.status.changed}; owns the PostgreSQL
 * {@code incident} schema; serves a read API for the web-ui Correlation Stats module.
 */
@SpringBootApplication
@EnableConfigurationProperties(CorrelationEngineProperties.class)
@EnableScheduling
public class CorrelationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorrelationEngineApplication.class, args);
    }
}
