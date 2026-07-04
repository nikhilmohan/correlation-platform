package com.acp.correlationengine.observability;

/**
 * Metrics port for the correlation core (kept Kafka/Micrometer-agnostic). The production
 * implementation ({@code MicrometerCorrelationMetrics}) registers Prometheus counters/gauges;
 * unit tests use a no-op or counting double.
 */
public interface CorrelationMetrics {

    void incrementAlarmsProcessed();

    void incrementIncidentsCreated();

    void incrementPatternMatch();

    void incrementCodebookMatch();

    void incrementSessionExpiration();

    void incrementStatusChanged(String newStatus);

    void incrementDlqRouted();

    void incrementCodebookFetchFailure();

    void setActiveInstances(int count);

    // --- Pattern generalization (spec § Observability additions) -----------------------------

    /** Trail Builder fetch failures during compatibility index build ({@code trail_builder_fetch_errors_total}). */
    void incrementTrailBuilderFetchError();

    /** Index refreshes triggered by {@code patterns.approved} / {@code trails.built} ({@code pattern_generalization_index_refresh_total}). */
    void incrementIndexRefresh(String trigger);

    /** Patterns excluded because their required object types could not be resolved. */
    void incrementRequiredTypesUnresolved();

    /** Per-pattern compatible-trail-set size ({@code compatible_trails_per_pattern} gauge). */
    void setCompatibleTrailsForPattern(String patternId, int compatibleTrailCount);

    /** A no-op implementation for tests that do not assert on metrics. */
    CorrelationMetrics NOOP = new CorrelationMetrics() {
        @Override public void incrementAlarmsProcessed() { }
        @Override public void incrementIncidentsCreated() { }
        @Override public void incrementPatternMatch() { }
        @Override public void incrementCodebookMatch() { }
        @Override public void incrementSessionExpiration() { }
        @Override public void incrementStatusChanged(String newStatus) { }
        @Override public void incrementDlqRouted() { }
        @Override public void incrementCodebookFetchFailure() { }
        @Override public void setActiveInstances(int count) { }
        @Override public void incrementTrailBuilderFetchError() { }
        @Override public void incrementIndexRefresh(String trigger) { }
        @Override public void incrementRequiredTypesUnresolved() { }
        @Override public void setCompatibleTrailsForPattern(String patternId, int count) { }
    };
}
