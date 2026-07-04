package com.acp.correlationengine.integration;

import com.acp.correlationengine.generalize.StartupSnapshotDiscovery;
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
 *
 * <p>It then DISCOVERS the current topology snapshot (via {@link StartupSnapshotDiscovery}) and builds
 * the compatibility index against it — so the index is populated immediately on restart, rather than
 * staying empty until a live {@code trails.built} event arrives (which, in a running system, was
 * already consumed and committed long ago). The {@code trails.built} consumer still rebuilds on any
 * NEW trail catalog thereafter.
 */
public class StartupBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrapRunner.class);

    private final PatternRefreshService patternRefresh;
    private final KnowledgeParamsProvider knowledgeParams;
    private final StartupSnapshotDiscovery snapshotDiscovery;

    public StartupBootstrapRunner(PatternRefreshService patternRefresh,
            KnowledgeParamsProvider knowledgeParams, StartupSnapshotDiscovery snapshotDiscovery) {
        this.patternRefresh = patternRefresh;
        this.knowledgeParams = knowledgeParams;
        this.snapshotDiscovery = snapshotDiscovery;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            knowledgeParams.bootstrap();
            patternRefresh.bootstrap();
            // Discover the current topology snapshot and build the compatibility index against it
            // NOW (Topology GET /topology/snapshots, with an approved-pattern fallback) instead of
            // waiting for a live trails.built event that a running system already consumed.
            snapshotDiscovery.discoverAndBuild();
            log.info("Correlation Engine bootstrap complete (Knowledge params + approved patterns "
                    + "+ compatibility index)");
        } catch (RuntimeException e) {
            log.warn("Bootstrap incomplete; readiness held until dependencies recover", e);
        }
    }
}
