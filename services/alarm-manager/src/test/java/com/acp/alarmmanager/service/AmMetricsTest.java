package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Exercises the Micrometer counter wiring so /metrics exposes the documented series. */
class AmMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AmMetrics metrics = new AmMetrics(registry);

    @Test
    void incrementsSimpleAndTaggedCounters() {
        metrics.persisted();
        metrics.republished();
        metrics.correlationApplied();
        metrics.cleared();
        metrics.statusParkedForUnknownAlarm();
        metrics.clearParkedForUnknownAlarm();
        metrics.correlationForUnknownAlarm();
        metrics.statusApplied("in-progress");
        metrics.alarmsPurged(4);
        metrics.dlqRouted("alarms.enriched.live.dlq");

        assertThat(registry.counter("alarms_persisted_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("alarms_republished_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("correlation_results_applied_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("alarms_cleared_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("status_parked_for_unknown_alarm_total").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("clear_parked_for_unknown_alarm_total").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("correlation_for_unknown_alarm_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("live_alarms_purged_total").count()).isEqualTo(4.0);
        assertThat(registry.get("status_changes_applied_total").tag("newStatus", "in-progress")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dlq_routed_total").tag("topic", "alarms.enriched.live.dlq")
                .counter().count()).isEqualTo(1.0);
    }
}
