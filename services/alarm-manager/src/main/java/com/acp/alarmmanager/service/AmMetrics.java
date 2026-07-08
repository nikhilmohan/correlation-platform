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

    /**
     * A STATE status change arrived for a not-yet-persisted alarm and was PARKED (durably stored for
     * re-apply once the alarm is persisted) — NOT dropped. Named "..._parked_..." so dashboards read
     * it as a recoverable ordering-race park, not a lost/dropped event.
     */
    public void statusParkedForUnknownAlarm() {
        registry.counter("status_parked_for_unknown_alarm_total").increment();
    }

    /**
     * A {@code cleared} status change arrived for a not-yet-persisted alarm and was PARKED for
     * re-apply — NOT dropped. Named "..._parked_..." so dashboards read it as a recoverable
     * ordering-race park, not a lost/dropped clear.
     */
    public void clearParkedForUnknownAlarm() {
        registry.counter("clear_parked_for_unknown_alarm_total").increment();
    }

    public void correlationForUnknownAlarm() {
        registry.counter("correlation_for_unknown_alarm_total").increment();
    }

    /**
     * A status-sync STATE change was IGNORED because it would have downgraded a stronger lifecycle
     * state (a {@code correlated} alarm being clobbered back to {@code in-progress}/{@code open} by a
     * lagging sibling pattern-instance event). The state-precedence guard suppressed the out-of-order
     * downgrade. Tagged by the {@code from}/{@code to} states for dashboards.
     */
    public void downgradeIgnored(String from, String to) {
        Counter.builder("status_downgrade_ignored_total").tag("from", from).tag("to", to)
                .register(registry).increment();
    }
}
