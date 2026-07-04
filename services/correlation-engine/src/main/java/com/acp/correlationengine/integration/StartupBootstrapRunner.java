package com.acp.correlationengine.integration;

import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.pattern.PatternRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * At startup, seeds the pattern set from the Pattern Manager read API and eagerly loads the
 * Knowledge match-params. Readiness gates on both succeeding (the engine never invents thresholds —
 * no hard-coded defaults, AC21). Failures are logged; the readiness indicator stays down until both
 * succeed. Runs after the context is ready so the HTTP/Kafka layers are up.
 */
public class StartupBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrapRunner.class);

    private final PatternRefreshService patternRefresh;
    private final KnowledgeParamsProvider knowledgeParams;

    public StartupBootstrapRunner(PatternRefreshService patternRefresh,
            KnowledgeParamsProvider knowledgeParams) {
        this.patternRefresh = patternRefresh;
        this.knowledgeParams = knowledgeParams;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            knowledgeParams.bootstrap();
            patternRefresh.bootstrap();
            log.info("Correlation Engine bootstrap complete (Knowledge params + approved patterns)");
        } catch (RuntimeException e) {
            log.warn("Bootstrap incomplete; readiness held until dependencies recover", e);
        }
    }
}
