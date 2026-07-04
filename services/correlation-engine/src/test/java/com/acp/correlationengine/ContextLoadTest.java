package com.acp.correlationengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.api.IncidentQueryController;
import com.acp.correlationengine.api.StatsController;
import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full Spring context (Kafka + Postgres autoconfig disabled via the {@code test} profile;
 * in-memory persistence) to prove the wiring — controllers, engine core, stores, clients, stats,
 * readiness — is consistent and the app starts. The Kafka producer emitters are supplied as no-ops
 * here since no broker is present.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadTest {

    @TestConfiguration
    static class Emitters {
        @Bean
        CorrelationResultEmitter correlationResultEmitter() {
            return incident -> { };
        }

        @Bean
        AlarmStatusEmitter alarmStatusEmitter() {
            return new AlarmStatusEmitter() {
                @Override public void fireInProgress(String a, long t) { }
                @Override public void fireCorrelated(String a, long t) { }
                @Override public void fireRevertedOpen(String a, long t) { }
            };
        }
    }

    @Autowired
    CorrelationEngine engine;
    @Autowired
    IncidentQueryController incidentQueryController;
    @Autowired
    StatsController statsController;

    @Test
    void contextLoadsAndCoreBeansArePresent() {
        assertThat(engine).isNotNull();
        assertThat(incidentQueryController).isNotNull();
        assertThat(statsController).isNotNull();
        assertThat(statsController.stats().totalAlarmsProcessed()).isZero();
    }

    /** Sanity: the wired engine + stats produce a self-consistent snapshot. */
    @Test
    void statsSnapshotIsServable() {
        var view = statsController.stats();
        assertThat(view.confidenceDistribution()).containsKey("0.8-1.0");
        assertThat(view.rcaAccuracy()).isNull(); // production eval-mode off
    }
}
