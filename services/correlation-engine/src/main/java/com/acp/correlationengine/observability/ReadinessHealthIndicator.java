package com.acp.correlationengine.observability;

import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness gate — the engine is ready only once the Knowledge match-params have loaded at least
 * once (matching is held with no hard-coded defaults until then). Contributes to Actuator
 * {@code /health} readiness. Liveness + DB/Kafka connectivity are covered by the framework's own
 * indicators.
 */
public class ReadinessHealthIndicator implements HealthIndicator {

    private final KnowledgeParamsProvider knowledgeParams;

    public ReadinessHealthIndicator(KnowledgeParamsProvider knowledgeParams) {
        this.knowledgeParams = knowledgeParams;
    }

    @Override
    public Health health() {
        if (knowledgeParams.hasParams()) {
            return Health.up().withDetail("knowledgeParams", "loaded").build();
        }
        return Health.down().withDetail("knowledgeParams", "not-loaded").build();
    }
}
