package com.acp.patternmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Pattern Manager entrypoint — the single owner of the full pattern domain.
 *
 * <p>Consumes {@code patterns.mined}, enriches each mined pattern (RCA + structural validation +
 * codebook reconciliation + session-window derivation + explainability), persists it to the
 * Pattern Store as {@code draft}, drives the human-approval lifecycle via its HTTP API, and is the
 * sole emitter of {@code PatternDiscoveredEvent} / {@code PatternApprovedEvent}.
 */
@SpringBootApplication
@EnableKafka
@ConfigurationPropertiesScan
public class PatternManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatternManagerApplication.class, args);
    }
}
