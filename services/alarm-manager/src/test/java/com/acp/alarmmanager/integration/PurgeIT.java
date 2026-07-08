package com.acp.alarmmanager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.alarmmanager.api.dto.PurgeSummary;
import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.PendingStatus;
import com.acp.alarmmanager.domain.Role;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import com.acp.alarmmanager.service.AmMetrics;
import com.acp.alarmmanager.service.PurgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P3 live-state purge against the REAL migrated {@code live_alarm} schema (the live gate). Proves
 * the FK-safe DELETE order works against the actual {@code state_transition -> alarm} FK, the
 * counts are correct, the purge is idempotent, and a co-located OTHER-schema table survives
 * (single-owner scope: the purge only clears {@code live_alarm}).
 */
class PurgeIT extends PostgresIntegrationBase {

    private JdbcTemplate jdbc;
    private PurgeService purge;
    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private PendingStatusRepository pendingStatus;
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void setUp() {
        DataSource ds = dataSource();
        migrate(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("TRUNCATE live_alarm.state_transition, live_alarm.pending_status, "
                + "live_alarm.alarm, live_alarm.processed_event RESTART IDENTITY CASCADE");

        alarms = new AlarmRepository(jdbc);
        transitions = new StateTransitionRepository(jdbc);
        pendingStatus = new PendingStatusRepository(jdbc);
        processedEvents = new ProcessedEventRepository(jdbc);

        AmMetrics metrics = new AmMetrics(new SimpleMeterRegistry());
        PurgeService bare = new PurgeService(transitions, pendingStatus, processedEvents, alarms,
                metrics);
        // Wrap the @Transactional method in a real transaction so a partial failure would roll back.
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        this.purge = new PurgeService(transitions, pendingStatus, processedEvents, alarms, metrics) {
            @Override
            public PurgeSummary purgeLiveAlarms() {
                return tx.execute(status -> bare.purgeLiveAlarms());
            }
        };
    }

    private AlarmRecord record(String alarmId) {
        Instant now = Instant.parse("2026-06-13T09:00:00Z");
        return new AlarmRecord(alarmId, "Port:ne1-1-1", "communicationsAlarm", "lossOfSignal",
                "PortDown", "critical", "raised", now, null, List.of("trail-77"), null,
                LifecycleState.OPEN, Role.NONE, null, false, "{}", now, now);
    }

    private void seed() {
        alarms.insertIfAbsent(record("ALM-1"));
        alarms.insertIfAbsent(record("ALM-2"));
        transitions.append("ALM-1", "open", "ingest", null, null, "e1", Instant.now());
        transitions.append("ALM-2", "open", "ingest", null, null, "e2", Instant.now());
        pendingStatus.upsert(new PendingStatus("ALM-9", "correlated", "correlation-engine",
                Instant.now(), "e3", Instant.now()));
        processedEvents.claim("e1", Instant.now());
        processedEvents.claim("e2", Instant.now());
    }

    @Test
    void purgesAllFourLiveAlarmTablesInFkSafeOrderWithCounts() {
        seed();

        PurgeSummary summary = purge.purgeLiveAlarms();

        assertThat(summary.purgedAlarms()).isEqualTo(2);
        assertThat(summary.purgedTransitions()).isEqualTo(2);
        assertThat(summary.purgedPendingStatus()).isEqualTo(1);
        assertThat(summary.purgedProcessedEvents()).isEqualTo(2);

        assertThat(count("live_alarm.alarm")).isZero();
        assertThat(count("live_alarm.state_transition")).isZero();
        assertThat(count("live_alarm.pending_status")).isZero();
        assertThat(count("live_alarm.processed_event")).isZero();
    }

    @Test
    void isIdempotentSecondPurgeReturnsZeros() {
        seed();
        purge.purgeLiveAlarms();

        PurgeSummary again = purge.purgeLiveAlarms();

        assertThat(again.purgedAlarms()).isZero();
        assertThat(again.purgedTransitions()).isZero();
        assertThat(again.purgedPendingStatus()).isZero();
        assertThat(again.purgedProcessedEvents()).isZero();
    }

    @Test
    void leavesOtherSchemaDataUntouched() {
        seed();
        // A co-located OTHER schema (stands in for noise_filter/pattern/etc.) the purge must NOT touch.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS other_domain");
        jdbc.execute("CREATE TABLE IF NOT EXISTS other_domain.keepme (id int primary key)");
        jdbc.update("INSERT INTO other_domain.keepme (id) VALUES (1), (2)");

        purge.purgeLiveAlarms();

        assertThat(count("other_domain.keepme")).isEqualTo(2);
    }

    private int count(String table) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }
}
