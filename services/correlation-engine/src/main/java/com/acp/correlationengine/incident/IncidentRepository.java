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
