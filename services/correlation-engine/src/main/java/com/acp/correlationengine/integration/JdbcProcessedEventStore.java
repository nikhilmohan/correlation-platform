package com.acp.correlationengine.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL {@link ProcessedEventStore} over {@code incident.processed_event}. The primary-key
 * {@code INSERT ... ON CONFLICT DO NOTHING} makes the mark atomic and idempotent — a redelivered
 * {@code eventId} inserts zero rows and is reported as already-seen.
 */
public class JdbcProcessedEventStore implements ProcessedEventStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProcessedEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markIfNew(String scope, String eventId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("dedupeKey", scope + "::" + eventId)
                .addValue("scope", scope);
        int inserted = jdbc.update("""
                INSERT INTO incident.processed_event (dedupe_key, scope)
                VALUES (:dedupeKey, :scope)
                ON CONFLICT (dedupe_key) DO NOTHING
                """, p);
        return inserted > 0;
    }
}
