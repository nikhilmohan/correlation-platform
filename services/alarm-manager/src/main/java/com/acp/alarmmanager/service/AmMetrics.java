package com.acp.alarmmanager.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Micrometer counters for the Alarm Manager (Prometheus-format on {@code /metrics}). */
@Component
public class AmMetrics {

    private final MeterRegistry registry;

    public AmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void persisted() {
        registry.counter("alarms_persisted_total").increment();
    }

    public void republished() {
        registry.counter("alarms_republished_total").increment();
    }

    public void statusApplied(String newStatus) {
        Counter.builder("status_changes_applied_total").tag("newStatus", newStatus)
                .register(registry).increment();
    }

    public void correlationApplied() {
        registry.counter("correlation_results_applied_total").increment();
    }

    public void cleared() {
        registry.counter("alarms_cleared_total").increment();
    }

    public void dlqRouted(String topic) {
        Counter.builder("dlq_routed_total").tag("topic", topic).register(registry).increment();
    }

    public void statusForUnknownAlarm() {
        registry.counter("status_for_unknown_alarm_total").increment();
    }

    public void clearForUnknownAlarm() {
        registry.counter("clear_for_unknown_alarm_total").increment();
    }

    public void correlationForUnknownAlarm() {
        registry.counter("correlation_for_unknown_alarm_total").increment();
    }
}
