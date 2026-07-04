package com.acp.correlationengine.integration;

import com.acp.correlationengine.generalize.CompatibilityIndexService;
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
    private final CompatibilityIndexService compatibilityIndex;

    public StartupBootstrapRunner(PatternRefreshService patternRefresh,
            KnowledgeParamsProvider knowledgeParams, CompatibilityIndexService compatibilityIndex) {
        this.patternRefresh = patternRefresh;
        this.knowledgeParams = knowledgeParams;
        this.compatibilityIndex = compatibilityIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            knowledgeParams.bootstrap();
            patternRefresh.bootstrap();
            // Best-effort full compatibility-index build. No-ops until a topology snapshot is known
            // (learned from a trails.built event); the trails.built consumer rebuilds thereafter.
            compatibilityIndex.rebuildAll(null, null);
            log.info("Correlation Engine bootstrap complete (Knowledge params + approved patterns "
                    + "+ compatibility index)");
        } catch (RuntimeException e) {
            log.warn("Bootstrap incomplete; readiness held until dependencies recover", e);
        }
    }
}
