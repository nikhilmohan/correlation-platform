package com.acp.topology.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness gate on the NebulaGraph space being usable (EH-11). Reports DOWN until the startup
 * bootstrap (ADD HOSTS, CREATE SPACE, schema) has completed. Reports no NebulaGraph connection
 * detail (AC-19).
 */
@Component("nebulaGraph")
public class NebulaGraphHealthIndicator implements HealthIndicator {

    private final StartupBootstrapRunner bootstrapRunner;

    public NebulaGraphHealthIndicator(StartupBootstrapRunner bootstrapRunner) {
        this.bootstrapRunner = bootstrapRunner;
    }

    @Override
    public Health health() {
        return bootstrapRunner.isGraphReady()
                ? Health.up().withDetail("space", "usable").build()
                : Health.down().withDetail("space", "bootstrapping").build();
    }
}
