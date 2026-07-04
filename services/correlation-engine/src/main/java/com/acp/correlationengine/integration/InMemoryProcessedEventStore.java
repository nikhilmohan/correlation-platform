package com.acp.correlationengine.integration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProcessedEventStore} — the default when no PostgreSQL is configured (unit tests
 * and the mock profile). The JDBC implementation mirrors this behaviour against
 * {@code incident.processed_event}.
 */
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markIfNew(String scope, String eventId) {
        return seen.add(scope + "::" + eventId);
    }
}
