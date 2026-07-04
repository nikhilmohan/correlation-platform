package com.acp.enrichment.observability;

import com.acp.enrichment.ruleset.RulesetRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness is gated on the {@link RulesetRegistry} having loaded a valid ruleset set (including a
 * {@code default}); design "Operational endpoints". Until then {@code /actuator/health/readiness}
 * reports DOWN so the service is not routed alarms it cannot enrich with known config (E2E scenario
 * 8). Registered under the {@code readiness} group via {@code application.yml}.
 */
@Component("rulesets")
public class RulesetReadinessIndicator implements HealthIndicator {

    private final RulesetRegistry registry;

    public RulesetReadinessIndicator(RulesetRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (registry.isLoaded()) {
            return Health.up()
                    .withDetail("rulesets", registry.snapshot().bySource().size())
                    .build();
        }
        return Health.down().withDetail("reason", "no valid ruleset set loaded").build();
    }
}
