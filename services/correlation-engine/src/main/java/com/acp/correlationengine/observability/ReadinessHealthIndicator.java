package com.acp.correlationengine.observability;

import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness gate — the engine is ready only once the Knowledge match-params have loaded at least
 * once (matching is held with no hard-coded defaults until then). Contributes to Actuator
 * {@code /health} readiness. Liveness + DB/Kafka connectivity are covered by the framework's own
 * indicators.
 *
 * <p>The compatibility-index build state is surfaced as a detail (spec § Observability): it becomes
 * {@code built} after the first successful {@code rebuildAll()} (once a snapshot exists). It is not a
 * hard readiness gate because in P1/P2 no trail snapshot exists yet and the engine has no correlation
 * work — holding readiness there would be wrong. It flips to {@code built} in P3 once the topology is
 * onboarded.
 */
public class ReadinessHealthIndicator implements HealthIndicator {

    private final KnowledgeParamsProvider knowledgeParams;
    private final CompatibilityIndexService compatibilityIndex;

    public ReadinessHealthIndicator(KnowledgeParamsProvider knowledgeParams,
            CompatibilityIndexService compatibilityIndex) {
        this.knowledgeParams = knowledgeParams;
        this.compatibilityIndex = compatibilityIndex;
    }

    @Override
    public Health health() {
        String indexState = compatibilityIndex.isBuiltAtLeastOnce() ? "built" : "pending";
        if (knowledgeParams.hasParams()) {
            return Health.up()
                    .withDetail("knowledgeParams", "loaded")
                    .withDetail("compatibilityIndex", indexState)
                    .build();
        }
        return Health.down()
                .withDetail("knowledgeParams", "not-loaded")
                .withDetail("compatibilityIndex", indexState)
                .build();
    }
}
