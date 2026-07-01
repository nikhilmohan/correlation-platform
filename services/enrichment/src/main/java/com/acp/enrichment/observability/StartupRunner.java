package com.acp.enrichment.observability;

import com.acp.enrichment.config.EnrichmentProperties;
import com.acp.enrichment.ruleset.RulesetConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the ruleset configuration once the application context is ready. A bad base config
 * (missing file, unparseable YAML, no {@code default}) leaves the {@link RulesetRegistry}
 * unloaded, so the readiness probe stays DOWN and the service does not enrich with unknown config
 * (design "Loading and hot-reload", E2E scenario 8). A corrupt overlay degrades to base-YAML-only
 * inside the loader (non-fatal).
 *
 * <p>The load is logged but not re-thrown so the HTTP server (chatter API, {@code /openapi.json},
 * Actuator) and the Kafka listeners still start — readiness simply reports DOWN until valid config
 * is present (and can be reloaded without a restart when {@code ENRICHMENT_RULESETS_RELOAD=true}).
 */
@Component
public class StartupRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final RulesetConfigLoader loader;
    private final EnrichmentProperties props;

    public StartupRunner(RulesetConfigLoader loader, EnrichmentProperties props) {
        this.loader = loader;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            loader.loadInitial();
            log.info("ruleset configuration loaded from {}", props.getRulesetsFile());
        } catch (RuntimeException e) {
            log.error("ruleset configuration invalid ({}): readiness will stay DOWN until a valid "
                    + "rulesets file is present", e.getMessage());
        }
    }
}
