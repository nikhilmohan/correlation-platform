package com.acp.correlationengine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer/Prometheus implementation of {@link CorrelationMetrics}. Registers the counters/gauge
 * named in the spec's Observability section: {@code incidents_created_total},
 * {@code alarms_processed_total}, {@code pattern_match_total}, {@code codebook_match_total},
 * {@code instance_session_expirations_total}, {@code alarms_status_changed_total} (tagged by
 * {@code newStatus}), {@code dlq_routed_total}, plus a codebook-fetch-failure counter and a live
 * {@code active_instances} gauge.
 */
public class MicrometerCorrelationMetrics implements CorrelationMetrics {

    private final Counter alarmsProcessed;
    private final Counter incidentsCreated;
    private final Counter patternMatch;
    private final Counter codebookMatch;
    private final Counter sessionExpirations;
    private final Counter dlqRouted;
    private final Counter codebookFetchFailure;
    private final Counter trailBuilderFetchErrors;
    private final Counter requiredTypesUnresolved;
    private final MeterRegistry registry;
    private final AtomicInteger activeInstances = new AtomicInteger(0);
    private final Map<String, AtomicInteger> compatibleTrailsGauges = new ConcurrentHashMap<>();

    public MicrometerCorrelationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.alarmsProcessed = Counter.builder("alarms_processed_total")
                .description("Distinct live alarms consumed from alarms.persisted.live").register(registry);
        this.incidentsCreated = Counter.builder("incidents_created_total")
                .description("Committed incidents").register(registry);
        this.patternMatch = Counter.builder("pattern_match_total")
                .description("Incidents formed by a pattern-instance match").register(registry);
        this.codebookMatch = Counter.builder("codebook_match_total")
                .description("Incidents formed by a codebook decode").register(registry);
        this.sessionExpirations = Counter.builder("instance_session_expirations_total")
                .description("Correlation instances expired without a full match").register(registry);
        this.dlqRouted = Counter.builder("dlq_routed_total")
                .description("Poison messages routed to a DLQ").register(registry);
        this.codebookFetchFailure = Counter.builder("codebook_fetch_failure_total")
                .description("Failed codebook trail-signature fetches").register(registry);
        this.trailBuilderFetchErrors = Counter.builder("trail_builder_fetch_errors_total")
                .description("Trail Builder fetch failures during compatibility index build")
                .register(registry);
        this.requiredTypesUnresolved = Counter.builder("pattern_required_types_unresolved_total")
                .description("Patterns excluded because required object types could not be resolved")
                .register(registry);
        registry.gauge("active_instances", activeInstances);
    }

    @Override
    public void incrementAlarmsProcessed() {
        alarmsProcessed.increment();
    }

    @Override
    public void incrementIncidentsCreated() {
        incidentsCreated.increment();
    }

    @Override
    public void incrementPatternMatch() {
        patternMatch.increment();
    }

    @Override
    public void incrementCodebookMatch() {
        codebookMatch.increment();
    }

    @Override
    public void incrementSessionExpiration() {
        sessionExpirations.increment();
    }

    @Override
    public void incrementStatusChanged(String newStatus) {
        registry.counter("alarms_status_changed_total", "newStatus", newStatus).increment();
    }

    @Override
    public void incrementDlqRouted() {
        dlqRouted.increment();
    }

    @Override
    public void incrementCodebookFetchFailure() {
        codebookFetchFailure.increment();
    }

    @Override
    public void setActiveInstances(int count) {
        activeInstances.set(count);
    }

    @Override
    public void incrementTrailBuilderFetchError() {
        trailBuilderFetchErrors.increment();
    }

    @Override
    public void incrementIndexRefresh(String trigger) {
        registry.counter("pattern_generalization_index_refresh_total", "trigger", trigger).increment();
    }

    @Override
    public void incrementRequiredTypesUnresolved() {
        requiredTypesUnresolved.increment();
    }

    @Override
    public void setCompatibleTrailsForPattern(String patternId, int compatibleTrailCount) {
        compatibleTrailsGauges.computeIfAbsent(patternId, id -> {
            AtomicInteger g = new AtomicInteger(0);
            registry.gauge("compatible_trails_per_pattern", io.micrometer.core.instrument.Tags.of("patternId", id), g);
            return g;
        }).set(compatibleTrailCount);
    }
}
