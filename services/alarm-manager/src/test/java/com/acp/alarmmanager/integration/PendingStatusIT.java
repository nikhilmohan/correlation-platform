package com.acp.alarmmanager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import com.acp.alarmmanager.service.AlarmMapper;
import com.acp.alarmmanager.service.AlarmPersister;
import com.acp.alarmmanager.service.AmMetrics;
import com.acp.alarmmanager.service.LifecycleService;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * THE ordering-race regression test, end-to-end against real PostgreSQL. On the P3 live path the
 * Correlation Engine can fire {@code AlarmStatusChange(correlated)} BEFORE the Alarm Manager has
 * persisted that alarm from {@code alarms.enriched.live}. Previously that status change was dropped
 * and the alarm stayed {@code open} forever. This suite proves the park-and-re-apply fix:
 *
 * <ol>
 *   <li>a correlated status for a not-yet-persisted alarm is PARKED (not dropped), and ingesting
 *       that alarm re-applies it so the row ends {@code lifecycle_state = correlated};</li>
 *   <li>last-write-wins when in-progress then correlated both arrive before persist (ends
 *       correlated);</li>
 *   <li>the parked entry is deleted after apply;</li>
 *   <li>a redelivered ingest does not double-apply.</li>
 * </ol>
 *
 * Uses the real repositories + real {@link LifecycleService}/{@link AlarmPersister} wired directly
 * over a Testcontainers PostgreSQL (the raw-JDBC path the SQL actually runs on).
 */
class PendingStatusIT extends PostgresIntegrationBase {

    private JdbcTemplate jdbc;
    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private PendingStatusRepository pending;
    private LifecycleService lifecycle;
    private AlarmPersister persister;

    @BeforeEach
    void setUp() {
        DataSource ds = dataSource();
        migrate(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("TRUNCATE live_alarm.state_transition, live_alarm.alarm, "
                + "live_alarm.processed_event, live_alarm.pending_status RESTART IDENTITY CASCADE");
        AmMetrics metrics = new AmMetrics(new SimpleMeterRegistry());
        alarms = new AlarmRepository(jdbc);
        transitions = new StateTransitionRepository(jdbc);
        pending = new PendingStatusRepository(jdbc);
        lifecycle = new LifecycleService(alarms, transitions, pending, metrics);
        persister = new AlarmPersister(alarms, new AlarmMapper(new EventCodec()), lifecycle,
                metrics);
    }

    /**
     * THE bug's regression: correlated status races ahead of the alarm's persist. It must be parked
     * and, on ingest, re-applied so the alarm ends {@code correlated} (never stuck {@code open}).
     */
    @Test
    void correlatedStatusRacingIngestIsParkedThenAppliedOnPersist() {
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");

        // (1) correlated status arrives FIRST, for an alarm that does not yet exist -> parked.
        lifecycle.applyState("ALM-RACE", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());
        assertThat(alarms.findById("ALM-RACE")).isEmpty();
        assertThat(pending.find("ALM-RACE")).isPresent();

        // (2) the alarm now arrives on the ingest path and is persisted -> parked status re-applied.
        persister.persistOpen(alarmEnvelope("ALM-RACE"));

        AlarmRecord stored = alarms.findById("ALM-RACE").orElseThrow();
        assertThat(stored.lifecycleState()).isEqualTo(LifecycleState.CORRELATED);
        // parked entry consumed/deleted after apply
        assertThat(pending.find("ALM-RACE")).isEmpty();
        // both the ingest open and the re-applied correlated audit entries are recorded
        assertThat(transitions.findByAlarmOrdered("ALM-RACE"))
                .extracting("toState").containsExactly("open", "correlated");
    }

    /**
     * Last-write-wins: in-progress then correlated both arrive before the alarm persists. Only the
     * latest (correlated) is kept, so the alarm ends {@code correlated} once ingested.
     */
    @Test
    void lastWriteWinsInProgressThenCorrelatedBeforePersistEndsCorrelated() {
        lifecycle.applyState("ALM-LWW", LifecycleState.IN_PROGRESS, "correlation-engine",
                Instant.parse("2026-06-13T09:04:00Z"), "evt-ip", Instant.now());
        lifecycle.applyState("ALM-LWW", LifecycleState.CORRELATED, "correlation-engine",
                Instant.parse("2026-06-13T09:05:00Z"), "evt-corr", Instant.now());

        // Only the latest-by-changedAt parked status survives.
        assertThat(pending.find("ALM-LWW").orElseThrow().newStatus()).isEqualTo("correlated");

        persister.persistOpen(alarmEnvelope("ALM-LWW"));

        assertThat(alarms.findById("ALM-LWW").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
        assertThat(pending.find("ALM-LWW")).isEmpty();
    }

    /**
     * Out-of-order arrival: correlated (newer) arrives before in-progress (older). The older change
     * must NOT clobber the newer parked status (last-write-wins by changedAt, not by arrival).
     */
    @Test
    void lastWriteWinsIgnoresLaterArrivingOlderStatus() {
        lifecycle.applyState("ALM-OOO", LifecycleState.CORRELATED, "correlation-engine",
                Instant.parse("2026-06-13T09:05:00Z"), "evt-corr", Instant.now());
        // an older-timestamped in-progress arrives LATER — must not overwrite the correlated park
        lifecycle.applyState("ALM-OOO", LifecycleState.IN_PROGRESS, "correlation-engine",
                Instant.parse("2026-06-13T09:04:00Z"), "evt-ip", Instant.now());

        assertThat(pending.find("ALM-OOO").orElseThrow().newStatus()).isEqualTo("correlated");

        persister.persistOpen(alarmEnvelope("ALM-OOO"));
        assertThat(alarms.findById("ALM-OOO").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
    }

    /**
     * The parked entry is deleted after apply, and a redelivered ingest does not double-apply
     * (insertIfAbsent returns false on the second delivery, so re-apply runs only on first insert).
     */
    @Test
    void parkedEntryDeletedAndRedeliveredIngestDoesNotDoubleApply() {
        lifecycle.applyState("ALM-DUP", LifecycleState.CORRELATED, "correlation-engine",
                Instant.parse("2026-06-13T09:05:00Z"), "evt-corr", Instant.now());

        persister.persistOpen(alarmEnvelope("ALM-DUP"));   // first delivery inserts + re-applies
        persister.persistOpen(alarmEnvelope("ALM-DUP"));   // redelivery: no insert, no re-apply

        assertThat(alarms.findById("ALM-DUP").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
        assertThat(pending.find("ALM-DUP")).isEmpty();
        // exactly one correlated audit entry (the re-apply ran once, on the first insert only)
        long correlatedAudits = transitions.findByAlarmOrdered("ALM-DUP").stream()
                .filter(t -> "correlated".equals(t.toState())).count();
        assertThat(correlatedAudits).isEqualTo(1);
    }

    /**
     * THE residual park-vs-persist window, closed. Reproduces the exact interleaving the reviewer
     * flagged: the status transaction reads {@code exists()=false} and takes the park branch, but
     * the alarm's persist transaction commits DURING the park window (its own reapply ran before our
     * parked row was visible, so it drained nothing). Without the self-heal the parked row is
     * orphaned and the alarm stays {@code open} forever.
     *
     * <p>The park path re-checks {@code exists()} after upserting and, seeing the alarm now present,
     * drains the parked row itself. We drive that interleaving with a real {@link LifecycleService}
     * over a real DB, using an {@link AlarmRepository} that returns {@code exists()=false} on the
     * first probe (park branch) then persists the alarm and returns {@code true} on the re-check —
     * exactly the window. Asserts: alarm ends {@code correlated}, NO orphan pending row remains, and
     * EXACTLY ONE correlated audit row (no double-count).
     */
    @Test
    void parkPathSelfHealsWhenAlarmPersistsDuringTheParkWindow() {
        Instant changedAt = Instant.parse("2026-06-13T09:05:00Z");
        RaceAlarmRepository racing = new RaceAlarmRepository(jdbc, "ALM-WINDOW");
        LifecycleService raced = new LifecycleService(racing, transitions, pending,
                new AmMetrics(new SimpleMeterRegistry()));

        // Status arrives: first exists() probe (park branch) is false; RaceAlarmRepository persists
        // the alarm and the re-check exists() then sees it -> the park path drains it itself.
        raced.applyState("ALM-WINDOW", LifecycleState.CORRELATED, "correlation-engine", changedAt,
                "evt-corr", Instant.now());

        // The alarm is correlated (never stuck open) and the parked row is drained (no orphan).
        assertThat(alarms.findById("ALM-WINDOW").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
        assertThat(pending.find("ALM-WINDOW")).isEmpty();
        // Exactly one correlated audit — the atomic claim guarantees a single drainer applies.
        long correlatedAudits = transitions.findByAlarmOrdered("ALM-WINDOW").stream()
                .filter(t -> "correlated".equals(t.toState())).count();
        assertThat(correlatedAudits).isEqualTo(1);
    }

    /**
     * Drain-race idempotency at the DB level: a parked status, then TWO drain attempts (as the
     * park-path re-check AND the persist-path reapply can both fire). The atomic
     * {@code DELETE ... RETURNING} claim means only the first drainer removes-and-applies the row;
     * the second claims nothing and no-ops — so the alarm ends {@code correlated} with EXACTLY ONE
     * correlated audit (no double-count), and no orphan pending row survives.
     */
    @Test
    void twoDrainAttemptsClaimOnceSoAuditIsNotDoubleCounted() {
        // Persist the alarm first so both drain attempts take the apply branch.
        persister.persistOpen(alarmEnvelope("ALM-2DRAIN"));
        // Park a correlated status directly (simulate the orphaned window row).
        pending.upsert(new com.acp.alarmmanager.domain.PendingStatus("ALM-2DRAIN", "correlated",
                "correlation-engine", Instant.parse("2026-06-13T09:05:00Z"), "evt-corr",
                Instant.now()));

        lifecycle.reapplyPending("ALM-2DRAIN", Instant.now());   // winner: claims + applies
        lifecycle.reapplyPending("ALM-2DRAIN", Instant.now());   // loser: claim empty, no-op

        assertThat(alarms.findById("ALM-2DRAIN").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
        assertThat(pending.find("ALM-2DRAIN")).isEmpty();
        long correlatedAudits = transitions.findByAlarmOrdered("ALM-2DRAIN").stream()
                .filter(t -> "correlated".equals(t.toState())).count();
        assertThat(correlatedAudits).isEqualTo(1);
    }

    /** A known alarm: a correlated status applies immediately and nothing is parked (unchanged). */
    @Test
    void knownAlarmAppliesImmediatelyWithoutParking() {
        persister.persistOpen(alarmEnvelope("ALM-KNOWN"));
        assertThat(alarms.findById("ALM-KNOWN").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.OPEN);

        lifecycle.applyState("ALM-KNOWN", LifecycleState.CORRELATED, "correlation-engine",
                Instant.parse("2026-06-13T09:05:00Z"), "evt-corr", Instant.now());

        assertThat(alarms.findById("ALM-KNOWN").orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CORRELATED);
        assertThat(pending.find("ALM-KNOWN")).isEmpty();
    }

    private static TypedEnvelope<Object> alarmEnvelope(String alarmId) {
        return Fixtures.CODEC.deserialize(Fixtures.alarmEventJson(
                "evt-ing-" + alarmId, alarmId, "PortDown", "raised", List.of("trail-77"),
                "2026-06-13T09:00:00Z"));
    }

    /**
     * An {@link AlarmRepository} that reproduces the park-vs-persist window for a single target
     * alarm: the FIRST {@link #exists} probe returns {@code false} (so {@code applyState} takes the
     * park branch), then — simulating the persist transaction committing during the park window —
     * it actually persists the alarm row against the real DB, so every SUBSEQUENT {@code exists}
     * (the park-path re-check) returns {@code true}. All other operations delegate to the real
     * repository against the real DB, so the drain writes real rows.
     */
    private static final class RaceAlarmRepository extends AlarmRepository {
        private final JdbcTemplate jdbc;
        private final String target;
        private boolean probed;

        RaceAlarmRepository(JdbcTemplate jdbc, String target) {
            super(jdbc);
            this.jdbc = jdbc;
            this.target = target;
        }

        @Override
        public boolean exists(String alarmId) {
            if (target.equals(alarmId) && !probed) {
                probed = true;   // first probe: alarm not yet visible -> park branch
                return false;
            }
            if (target.equals(alarmId) && !super.exists(alarmId)) {
                // The persist tx commits during the park window: insert the alarm row now, so the
                // park-path re-check sees it and drains the parked status.
                insertTargetAlarm();
            }
            return super.exists(alarmId);
        }

        private void insertTargetAlarm() {
            jdbc.update("""
                    INSERT INTO live_alarm.alarm (
                      alarm_id, managed_object_id, event_type, probable_cause, alarm_type,
                      perceived_severity, wire_state, raised_at, trail_ids, vendor_raw,
                      lifecycle_state, role, published, raw_envelope, created_at, updated_at)
                    VALUES (?, 'MO-1', 'PortDown', 'pc', 'PortDown', 'major', 'raised', now(),
                      '[]'::jsonb, '{}'::jsonb, 'open', 'none', false, '{}', now(), now())
                    ON CONFLICT (alarm_id) DO NOTHING
                    """, target);
        }
    }
}
