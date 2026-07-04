package com.acp.correlationengine.knowledge;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches + caches the match-quality/conflict params from the {@link KnowledgeClient} with a TTL
 * refresh. On a fetch failure it serves the last-known cached params and logs a warning; if no
 * params were ever loaded (the seeded record never fetched) {@link #hasParams()} is false and
 * readiness fails — the engine never invents defaults (no hard-coded thresholds, AC21).
 */
public class KnowledgeParamsProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeParamsProvider.class);

    private final KnowledgeClient client;
    private final long refreshMillis;
    private final AtomicReference<MatchParams> cached = new AtomicReference<>();
    private volatile long lastFetchEpochMs = 0L;

    public KnowledgeParamsProvider(KnowledgeClient client, long refreshMillis) {
        this.client = client;
        this.refreshMillis = refreshMillis;
    }

    /** Eagerly load params (called at startup); readiness depends on this succeeding at least once. */
    public synchronized void bootstrap() {
        refresh();
    }

    /**
     * @return the current params, refreshing from Knowledge if the TTL has elapsed. On refresh
     *     failure the last-known cached params are returned.
     * @throws KnowledgeUnavailableException if no params have EVER been loaded and the fetch fails
     */
    public MatchParams current() {
        long now = System.currentTimeMillis();
        if (cached.get() == null || now - lastFetchEpochMs >= refreshMillis) {
            refresh();
        }
        MatchParams params = cached.get();
        if (params == null) {
            throw new KnowledgeUnavailableException(
                    "no Knowledge match-params loaded; matching is held (no hard-coded defaults)");
        }
        return params;
    }

    private void refresh() {
        try {
            MatchParams fresh = client.fetchMatchParams();
            cached.set(fresh);
            lastFetchEpochMs = System.currentTimeMillis();
        } catch (RuntimeException e) {
            if (cached.get() == null) {
                log.warn("Knowledge model-params fetch failed and no cached value exists", e);
                throw e;
            }
            log.warn("Knowledge model-params refresh failed; serving last-known cached params", e);
        }
    }

    /** @return true once params have been loaded at least once (readiness gate). */
    public boolean hasParams() {
        return cached.get() != null;
    }
}
