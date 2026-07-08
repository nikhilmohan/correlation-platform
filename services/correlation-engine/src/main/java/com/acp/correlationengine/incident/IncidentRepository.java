package com.acp.correlationengine.incident;

import com.acp.correlationengine.model.Incident;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence + query port for the Incident Store (the system of record). Implemented over
 * PostgreSQL (the owned {@code incident} schema) in production and in-memory for unit tests.
 * All persistence is idempotent on {@code instance_fingerprint} (AC16).
 */
public interface IncidentRepository {

    /**
     * Persist the incident + its membership idempotently. A row with the same
     * {@code instance_fingerprint} is a no-op.
     *
     * @return true if a new incident was inserted, false if it already existed
     */
    boolean save(Incident incident);

    /** @return the incident by id, if present. */
    Optional<Incident> findById(String incidentId);

    /** @return incidents matching the filter, most recent first, page-limited. */
    List<Incident> find(IncidentFilter filter);

    /** @return the count of incidents matching the filter (ignoring limit/offset). */
    long count(IncidentFilter filter);

    /** @return the total number of committed incidents. */
    long totalIncidents();

    /** @return the number of incidents by match type token ({@code "pattern"} / {@code "codebook"}). */
    long countByMatchType(String matchTypeToken);

    /** @return distinct {@code alarmId}s that hold any role in some committed incident (D1 numerator). */
    long distinctCorrelatedAlarmCount();

    /** @return confidence-bucket counts ({@code "0.0-0.2"} .. {@code "0.8-1.0"}). */
    java.util.Map<String, Long> confidenceDistribution();

    /**
     * Purge ALL rows the CE owns in its {@code incident} schema — the P3 live incident state
     * ({@code incident.incident} + {@code incident.incident_alarm}) — for a demo/ops reset. This is
     * a transactional, all-or-nothing delete. It deliberately does NOT touch
     * {@code incident.processed_event}: that ledger dedupes P2 model events
     * ({@code patterns.approved} / {@code codebook.generated} / {@code trails.built}) whose
     * {@code eventId}s must survive so the loaded P2 model is not re-ingested on redelivery.
     *
     * @return the purge counts (rows removed from each table)
     */
    PurgeCounts deleteAll();

    /**
     * The number of rows removed by {@link #deleteAll()} from each owned incident table.
     *
     * @param purgedIncidents rows removed from {@code incident.incident}
     * @param purgedIncidentAlarms rows removed from {@code incident.incident_alarm}
     */
    record PurgeCounts(long purgedIncidents, long purgedIncidentAlarms) {
    }

    /** Filter for {@link #find} / {@link #count}. */
    record IncidentFilter(
            String trailId,
            Instant from,
            Instant to,
            String matchType,
            int limit,
            int offset) {
    }
}
