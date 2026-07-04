package com.acp.correlationengine.incident;

import com.acp.correlationengine.model.Incident;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-memory {@link IncidentRepository} — the default implementation used when no PostgreSQL is
 * configured (unit tests, and the mock profile). Idempotent on {@code instance_fingerprint}. The
 * JDBC implementation mirrors this behaviour against the owned {@code incident} schema.
 */
public class InMemoryIncidentRepository implements IncidentRepository {

    private final Map<String, Incident> byId = new LinkedHashMap<>();
    private final Set<String> fingerprints = new LinkedHashSet<>();

    @Override
    public synchronized boolean save(Incident incident) {
        if (fingerprints.contains(incident.instanceFingerprint())
                || byId.containsKey(incident.incidentId())) {
            return false;
        }
        byId.put(incident.incidentId(), incident);
        fingerprints.add(incident.instanceFingerprint());
        return true;
    }

    @Override
    public synchronized Optional<Incident> findById(String incidentId) {
        return Optional.ofNullable(byId.get(incidentId));
    }

    @Override
    public synchronized List<Incident> find(IncidentFilter filter) {
        return matching(filter)
                .sorted(Comparator.comparing(Incident::createdAt).reversed())
                .skip(Math.max(0, filter.offset()))
                .limit(Math.max(0, filter.limit()))
                .toList();
    }

    @Override
    public synchronized long count(IncidentFilter filter) {
        return matching(filter).count();
    }

    private Stream<Incident> matching(IncidentFilter filter) {
        return byId.values().stream()
                .filter(i -> filter.trailId() == null || filter.trailId().equals(i.trailId()))
                .filter(i -> filter.matchType() == null || filter.matchType().equals(i.matchTypeToken()))
                .filter(i -> filter.from() == null || !i.createdAt().isBefore(filter.from()))
                .filter(i -> filter.to() == null || !i.createdAt().isAfter(filter.to()));
    }

    @Override
    public synchronized long totalIncidents() {
        return byId.size();
    }

    @Override
    public synchronized long countByMatchType(String matchTypeToken) {
        return byId.values().stream().filter(i -> i.matchTypeToken().equals(matchTypeToken)).count();
    }

    @Override
    public synchronized long distinctCorrelatedAlarmCount() {
        Set<String> distinct = new LinkedHashSet<>();
        for (Incident i : byId.values()) {
            distinct.add(i.rootCauseAlarmId());
            distinct.addAll(i.childAlarmIds());
        }
        return distinct.size();
    }

    @Override
    public synchronized Map<String, Long> confidenceDistribution() {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String b : List.of("0.0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1.0")) {
            buckets.put(b, 0L);
        }
        for (Incident i : byId.values()) {
            buckets.merge(bucket(i.confidence()), 1L, Long::sum);
        }
        return buckets;
    }

    static String bucket(double confidence) {
        if (confidence < 0.2) {
            return "0.0-0.2";
        }
        if (confidence < 0.4) {
            return "0.2-0.4";
        }
        if (confidence < 0.6) {
            return "0.4-0.6";
        }
        if (confidence < 0.8) {
            return "0.6-0.8";
        }
        return "0.8-1.0";
    }

    /** Test helper: for demonstrating the distinct-count semantics with an explicit instant. */
    static Instant now() {
        return Instant.now();
    }
}
