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
    };
}
