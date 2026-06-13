package com.acp.topology.observability;

import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.GraphRepository;
import com.acp.topology.graph.OrphanReaper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application ready (Flow E): idempotently bootstrap the NebulaGraph space/schema (ADD HOSTS +
 * CREATE SPACE/TAG/EDGE/INDEX IF NOT EXISTS), then run the orphan-snapshot reaper. Sets a flag that
 * the {@link NebulaGraphHealthIndicator} reports as readiness. Bootstrap can be disabled in tests
 * via {@code topology.nebula.bootstrap-on-startup=false}.
 */
@Component
public class StartupBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrapRunner.class);

    private final GraphRepository graphRepository;
    private final OrphanReaper orphanReaper;
    private final boolean bootstrapEnabled;
    private final AtomicBoolean graphReady = new AtomicBoolean(false);

    public StartupBootstrapRunner(GraphRepository graphRepository, OrphanReaper orphanReaper,
            TopologyProperties properties) {
        this.graphRepository = graphRepository;
        this.orphanReaper = orphanReaper;
        this.bootstrapEnabled = properties.getNebula().isBootstrapOnStartup();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!bootstrapEnabled) {
            log.info("NebulaGraph bootstrap disabled (topology.nebula.bootstrap-on-startup=false)");
            return;
        }
        try {
            graphRepository.bootstrapSchema();
            int reaped = orphanReaper.reap();
            graphReady.set(true);
            log.info("startup bootstrap complete; orphan snapshots reaped={}", reaped);
        } catch (Exception e) {
            graphReady.set(false);
            log.error("startup bootstrap failed; readiness will report DOWN", e);
        }
    }

    public boolean isGraphReady() {
        return graphReady.get();
    }
}
