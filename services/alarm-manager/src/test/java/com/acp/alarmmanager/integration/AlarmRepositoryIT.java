package com.acp.alarmmanager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import com.acp.alarmmanager.repository.AlarmQueryFilter;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repository round-trip against real PostgreSQL (the live gate for the raw-JDBC repos).
 * AC1/AC3 — idempotent upsert on alarmId. AC8/9/10/11/19/22 — filtered queries + alarmType
 * round-trip. AC18 — disjoint STATE vs. ROLE columns. Plus processed_event + partial-unique guards.
 */
class AlarmRepositoryIT extends PostgresIntegrationBase {

    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private ProcessedEventRepository processed;

    @BeforeEach
    void setUp() {
        DataSource ds = dataSource();
        migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("TRUNCATE live_alarm.state_transition, live_alarm.alarm, "
                + "live_alarm.processed_event RESTART IDENTITY CASCADE");
        alarms = new AlarmRepository(jdbc);
        transitions = new StateTransitionRepository(jdbc);
        processed = new ProcessedEventRepository(jdbc);
    }

    private AlarmRecord record(String alarmId, LifecycleState state, String incidentId,
            List<String> trails, Instant raisedAt) {
        Instant now = Instant.parse("2026-06-13T09:00:00Z");
        return new AlarmRecord(alarmId, "Port:ne1-1-1", "communicationsAlarm", "lossOfSignal",
                "PortDown", "critical", "raised", raisedAt, null, trails, null, state, Role.NONE,
                incidentId, false, "{}", now, now);
    }

    @Test
    void upsertIsIdempotentOnAlarmId() {
        AlarmRecord r = record("ALM-1", LifecycleState.OPEN, null, List.of("trail-77"),
                Instant.parse("2026-06-13T09:00:00Z"));

        assertThat(alarms.insertIfAbsent(r)).isTrue();
        assertThat(alarms.insertIfAbsent(r)).isFalse();

        AlarmRecord stored = alarms.findById("ALM-1").orElseThrow();
        assertThat(stored.alarmType()).isEqualTo("PortDown");
        assertThat(stored.eventType()).isEqualTo("communicationsAlarm");
        assertThat(stored.probableCause()).isEqualTo("lossOfSignal");
        assertThat(stored.lifecycleState()).isEqualTo(LifecycleState.OPEN);
        assertThat(stored.trailIds()).containsExactly("trail-77");
    }

    @Test
    void publishedGuardFlipsExactlyOnce() {
        alarms.insertIfAbsent(record("ALM-2", LifecycleState.OPEN, null, List.of(),
                Instant.parse("2026-06-13T09:00:00Z")));
        assertThat(alarms.markPublished("ALM-2", Instant.now())).isTrue();
        assertThat(alarms.markPublished("ALM-2", Instant.now())).isFalse();
    }

    @Test
    void partialUniqueGuardAllowsOneIngestOpenAudit() {
        alarms.insertIfAbsent(record("ALM-3", LifecycleState.OPEN, null, List.of(),
                Instant.parse("2026-06-13T09:00:00Z")));
        transitions.append("ALM-3", "open", "ingest", null, null, "e1", Instant.now());
        // redelivery — same ingest-open is swallowed by the partial unique guard
        transitions.append("ALM-3", "open", "ingest", null, null, "e1", Instant.now());
        assertThat(transitions.findByAlarmOrdered("ALM-3")).hasSize(1);
    }

    @Test
    void stateAndRoleAreDisjointColumns() {
        alarms.insertIfAbsent(record("ALM-4", LifecycleState.OPEN, null, List.of(),
                Instant.parse("2026-06-13T09:00:00Z")));
        // ROLE channel sets role + incident, leaves lifecycle_state open
        alarms.updateRoleAndIncident("ALM-4", Role.ROOT_CAUSE, "INC-1", Instant.now());
        assertThat(alarms.findById("ALM-4").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.OPEN);
        // STATE channel sets lifecycle_state, leaves role/incident
        alarms.updateLifecycleState("ALM-4", LifecycleState.CORRELATED, null, Instant.now());
        AlarmRecord after = alarms.findById("ALM-4").orElseThrow();
        assertThat(after.lifecycleState()).isEqualTo(LifecycleState.CORRELATED);
        assertThat(after.role()).isEqualTo(Role.ROOT_CAUSE);
        assertThat(after.incidentId()).isEqualTo("INC-1");
    }

    @Test
    void filtersByStateTrailIncidentAndTimeWindow() {
        alarms.insertIfAbsent(record("OPEN-A", LifecycleState.OPEN, null, List.of("trail-77"),
                Instant.parse("2026-06-13T09:00:00Z")));
        alarms.insertIfAbsent(record("IP-B", LifecycleState.IN_PROGRESS, "INC-9",
                List.of("trail-99"), Instant.parse("2026-06-13T12:00:00Z")));
        alarms.insertIfAbsent(record("CLR-C", LifecycleState.CLEARED, null, List.of("trail-77"),
                Instant.parse("2026-06-14T09:00:00Z")));

        // state=open only
        assertThat(ids(alarms.query(filter(LifecycleState.OPEN, null, null, null, null))))
                .containsExactly("OPEN-A");
        // state=in-progress only
        assertThat(ids(alarms.query(filter(LifecycleState.IN_PROGRESS, null, null, null, null))))
                .containsExactly("IP-B");
        // trailId membership
        assertThat(ids(alarms.query(filter(null, "trail-77", null, null, null))))
                .containsExactlyInAnyOrder("OPEN-A", "CLR-C");
        // incidentId
        assertThat(ids(alarms.query(filter(null, null, "INC-9", null, null))))
                .containsExactly("IP-B");
        // time window
        assertThat(ids(alarms.query(filter(null, null, null,
                Instant.parse("2026-06-13T08:00:00Z"), Instant.parse("2026-06-13T10:00:00Z")))))
                .containsExactly("OPEN-A");
    }

    @Test
    void processedEventGuardClaimsOnce() {
        assertThat(processed.claim("evt-1", Instant.now())).isTrue();
        assertThat(processed.claim("evt-1", Instant.now())).isFalse();
    }

    /**
     * B1 / AC17 — a FINALISED role/incidentId from a completed CorrelationResultEvent must SURVIVE
     * a later reverted-open. The revert returns STATE to open but preserves the finalised
     * root-cause role + incident linkage (Design alternatives, option (b)).
     */
    @Test
    void revertPreservesFinalisedRoleAndIncident() {
        alarms.insertIfAbsent(record("ALM-FIN", LifecycleState.CORRELATED, null, List.of(),
                Instant.parse("2026-06-13T09:00:00Z")));
        // completed CorrelationResultEvent finalises role + incident linkage
        alarms.updateRoleAndIncident("ALM-FIN", Role.ROOT_CAUSE, "INC-42", Instant.now());

        // late reverted-open arrives
        alarms.revertToOpenClearingProvisionalRole("ALM-FIN", Instant.now());

        AlarmRecord after = alarms.findById("ALM-FIN").orElseThrow();
        assertThat(after.lifecycleState()).isEqualTo(LifecycleState.OPEN);
        // finalised role + incidentId SURVIVE (not clobbered by the revert)
        assertThat(after.role()).isEqualTo(Role.ROOT_CAUSE);
        assertThat(after.incidentId()).isEqualTo("INC-42");
    }

    /**
     * B1 / AC17 — a PROVISIONAL role association (no finalised incident linkage) IS cleared to
     * {@code none} on reverted-open, while STATE returns to {@code open}.
     */
    @Test
    void revertClearsProvisionalRoleWithoutIncident() {
        alarms.insertIfAbsent(record("ALM-PROV", LifecycleState.IN_PROGRESS, null, List.of(),
                Instant.parse("2026-06-13T09:00:00Z")));
        // provisional in-progress role, deliberately NOT finalised (no incident_id)
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.update("UPDATE live_alarm.alarm SET role = 'child' WHERE alarm_id = 'ALM-PROV'");
        assertThat(alarms.findById("ALM-PROV").orElseThrow().role()).isEqualTo(Role.CHILD);

        alarms.revertToOpenClearingProvisionalRole("ALM-PROV", Instant.now());

        AlarmRecord after = alarms.findById("ALM-PROV").orElseThrow();
        assertThat(after.lifecycleState()).isEqualTo(LifecycleState.OPEN);
        // provisional role reset to none, still no incident linkage
        assertThat(after.role()).isEqualTo(Role.NONE);
        assertThat(after.incidentId()).isNull();
    }

    private AlarmQueryFilter filter(LifecycleState state, String trailId, String incidentId,
            Instant from, Instant to) {
        return new AlarmQueryFilter(state, trailId, incidentId, from, to, 50, 0);
    }

    private List<String> ids(List<AlarmRecord> records) {
        return records.stream().map(AlarmRecord::alarmId).toList();
    }
}
