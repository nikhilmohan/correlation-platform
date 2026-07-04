package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@link PatternStore} fresh from the Pattern Manager read API. Called at startup (bootstrap
 * seed) and on every {@code patterns.approved} event, which is treated as a REFRESH TRIGGER only —
 * the {@code trailId} placing each pattern on its trail(s) comes from {@code PatternView.trailId}
 * (the read API), never from the event (AC27). On a refresh failure the prior in-memory placements
 * are retained (graceful degradation).
 */
public class PatternRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PatternRefreshService.class);

    private final PatternManagerClient client;
    private final PatternStore store;

    public PatternRefreshService(PatternManagerClient client, PatternStore store) {
        this.client = client;
        this.store = store;
    }

    /** Seed the store at startup; readiness depends on this succeeding at least once. */
    public void bootstrap() {
        List<PatternRef> approved = client.listApproved();
        store.replaceAll(approved);
        log.info("Pattern bootstrap: {} approved patterns loaded", approved.size());
    }

    /**
     * Re-fetch the approved pattern set and refresh the store — the response to a
     * {@code patterns.approved} event (trigger). The {@code trailId} for each pattern comes from
     * {@code PatternView.trailId}.
     */
    public void refreshOnApproval() {
        try {
            List<PatternRef> approved = client.listApproved();
            store.replaceAll(approved);
            log.info("Pattern refresh on approval: {} approved patterns", approved.size());
        } catch (RuntimeException e) {
            log.warn("Pattern refresh failed; retaining prior in-memory placements", e);
        }
    }
}
