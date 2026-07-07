package com.acp.correlationengine.api;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.api.StatsAggregator.RcaAccuracyOracle;
import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression for the {@code /stats} scope-mismatch bugs. Two sibling ratios must each stay within
 * their natural bound, and both broke the same way — a since-restart in-memory count paired with an
 * all-time DB count:
 * <ul>
 *   <li>auto-correlation = {@code correlatedAlarmCount / totalAlarmsProcessed} — must be {@code <= 1}
 *       (live showed 154.1%: 279 all-time DB correlated / 181 since-restart processed);
 *   <li>alarm-reduction = {@code totalAlarmsProcessed / totalIncidentsCreated} — must be {@code >= 1}
 *       (an all-time DB incident denominator over a since-restart processed numerator can dip below
 *       1.0, the same impossible-number class).
 * </ul>
 *
 * <p>The fix makes EVERY count that participates in a ratio come from the SAME scope: the engine's own
 * in-memory session state ({@code processedAlarmIds}, {@code correlatedAlarmIds}, {@code firedIncidentIds}),
 * which share one lifetime and reset together on restart. Because a correlated alarm is necessarily an
 * ingested alarm, and every fired incident consumes at least its root-cause alarm,
 * {@code correlatedAlarmCount <= totalAlarmsProcessed} and {@code totalIncidentsCreated <= totalAlarmsProcessed}
 * both hold — so auto-correlation stays in {@code [0, 1]} and alarm-reduction stays {@code >= 1} across
 * restarts.
 */
class StatsScopeConsistencyTest {

    /**
     * Reproduces the exact production scope mismatch: an all-time-DB numerator paired with a
     * since-restart denominator. Even when the Incident Store carries pre-existing incidents from
     * prior runs (all-time history), the {@code /stats} numerator must NOT be sourced from it — the
     * rate must stay {@code <= 1.0} because both counts come from the engine's current session.
     */
    @Test
    void allTimeNumeratorWithSinceRestartDenominator_rateStaysAtMostOne() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // Simulate the DB carrying a large all-time correlated-alarm history from PRIOR runs — the
        // shape that, when used as the numerator against a fresh since-restart denominator, produced
        // 279/181 = 154% live.
        seedPriorHistory(h.incidents, 279);

        // This session (post-restart): only a handful of alarms processed so far.
        h.feed(alarm("s1", "LOS"), "T", T0);
        h.feed(alarm("s2", "LinkDown"), "T", T0 + 1); // -> 1 incident this session, 2 correlated
        h.feed(alarm("s3", "CardFault"), "T", T0 + 2); // uncorrelated noise

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        StatsView stats = agg.snapshot();

        // Invariant: 0 <= correlatedAlarmCount <= totalAlarmsProcessed, so rate in [0, 1].
        assertThat(stats.totalAlarmsProcessed()).isEqualTo(3);
        assertThat(stats.correlatedAlarmCount())
                .isGreaterThanOrEqualTo(0)
                .isLessThanOrEqualTo(stats.totalAlarmsProcessed());

        double rate = (double) stats.correlatedAlarmCount() / stats.totalAlarmsProcessed();
        assertThat(rate).isBetween(0.0, 1.0);
        // Specifically NOT the 279-from-the-DB numerator that caused >100%.
        assertThat(stats.correlatedAlarmCount()).isEqualTo(2);
    }

    /**
     * After processing N alarms of which C are correlated (C &lt; N), {@code /stats} gives
     * {@code correlatedAlarmCount == C}, {@code totalAlarmsProcessed == N}, rate {@code C/N <= 1}.
     */
    @Test
    void nAlarmsCCorrelated_rateIsCOverN_atMostOne() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // N = 5 processed, C = 4 correlated (two 2-alarm incidents), 1 uncorrelated noise alarm.
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1); // incident 1: a1 + a2
        h.feed(alarm("a3", "LOS"), "T", T0 + 2);
        h.feed(alarm("a4", "LinkDown"), "T", T0 + 3); // incident 2: a3 + a4
        h.feed(alarm("noise", "CardFault"), "T", T0 + 4); // uncorrelated

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        StatsView stats = agg.snapshot();

        assertThat(stats.totalAlarmsProcessed()).isEqualTo(5);
        assertThat(stats.correlatedAlarmCount()).isEqualTo(4);
        double rate = (double) stats.correlatedAlarmCount() / stats.totalAlarmsProcessed();
        assertThat(rate).isEqualTo(4.0 / 5.0).isLessThanOrEqualTo(1.0);
    }

    /**
     * A simulated restart resets BOTH counts together (they share the engine's session lifetime), so
     * the rate is never computed from a stale, larger all-time numerator over a fresh denominator.
     */
    @Test
    void acrossSimulatedRestart_rateStaysAtMostOne() {
        // Run 1: process alarms and correlate some, then discard the engine (== restart).
        EngineHarness run1 = new EngineHarness();
        run1.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));
        run1.feed(alarm("r1a", "LOS"), "T", T0);
        run1.feed(alarm("r1b", "LinkDown"), "T", T0 + 1); // correlated in run 1
        StatsView afterRun1 =
                new StatsAggregator(run1.incidents, run1.engine, RcaAccuracyOracle.DISABLED).snapshot();
        assertThat(afterRun1.correlatedAlarmCount()).isEqualTo(2);
        assertThat(afterRun1.totalAlarmsProcessed()).isEqualTo(2);

        // Run 2: a NEW engine (fresh in-memory counters), but the persistent Incident Store still
        // holds run 1's incidents (all-time history). Only 1 alarm processed so far this run.
        EngineHarness run2 = new EngineHarness();
        run2.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));
        // Carry run 1's all-time incident history into run 2's repository.
        seedPriorHistory(run2.incidents, 2);
        run2.feed(alarm("r2a", "LOS"), "T", T0 + 100); // opens an instance; not yet a full match

        StatsView afterRestart =
                new StatsAggregator(run2.incidents, run2.engine, RcaAccuracyOracle.DISABLED).snapshot();

        // Denominator reset to this session; numerator must NOT be the all-time 2 from the DB.
        assertThat(afterRestart.totalAlarmsProcessed()).isEqualTo(1);
        assertThat(afterRestart.correlatedAlarmCount())
                .isLessThanOrEqualTo(afterRestart.totalAlarmsProcessed());
        double rate =
                (double) afterRestart.correlatedAlarmCount() / afterRestart.totalAlarmsProcessed();
        assertThat(rate).isBetween(0.0, 1.0);
    }

    /**
     * SIBLING invariant (alarm-reduction ratio): after processing N alarms forming I incidents this
     * session, {@code totalAlarmsProcessed (N) >= totalIncidentsCreated (I)} and the alarm-reduction
     * ratio {@code N/I >= 1} — because {@code totalIncidentsCreated} is now the engine's session
     * incident count (same scope as {@code totalAlarmsProcessed}), not the all-time DB count. Each
     * incident consumes at least its root-cause alarm, so processed always dominates incidents.
     */
    @Test
    void nAlarmsIIncidents_alarmReductionAtLeastOne() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // N = 5 processed forming I = 2 incidents (each incident = 2 alarms), plus 1 noise alarm.
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1); // incident 1
        h.feed(alarm("a3", "LOS"), "T", T0 + 2);
        h.feed(alarm("a4", "LinkDown"), "T", T0 + 3); // incident 2
        h.feed(alarm("noise", "CardFault"), "T", T0 + 4); // uncorrelated

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        StatsView stats = agg.snapshot();

        assertThat(stats.totalAlarmsProcessed()).isEqualTo(5);
        assertThat(stats.totalIncidentsCreated()).isEqualTo(2);
        assertThat(stats.totalAlarmsProcessed())
                .isGreaterThanOrEqualTo(stats.totalIncidentsCreated());
        double alarmReduction =
                (double) stats.totalAlarmsProcessed() / stats.totalIncidentsCreated();
        assertThat(alarmReduction).isEqualTo(5.0 / 2.0).isGreaterThanOrEqualTo(1.0);
        // pattern + codebook match counts are also session-scoped: sum == session incident count.
        assertThat(stats.patternMatchCount() + stats.codebookMatchCount())
                .isEqualTo(stats.totalIncidentsCreated());
    }

    /**
     * SIBLING invariant across a simulated restart: with the Incident Store carrying many prior-run
     * incidents (all-time history &gt; this session's processed alarms), the alarm-reduction ratio must
     * still be {@code >= 1} — proving {@code totalIncidentsCreated} is the session count, not the stale
     * all-time DB count. This is the exact class the reviewer flagged: an all-time incident denominator
     * paired with a since-restart processed numerator can drive the ratio below 1.0.
     */
    @Test
    void acrossSimulatedRestart_alarmReductionStaysAtLeastOne() {
        EngineHarness run2 = new EngineHarness();
        run2.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // DB carries 80 prior-run incidents (all-time). If totalIncidentsCreated were DB-sourced, the
        // ratio would be (this-session processed) / 80 << 1.0 — the impossible-number bug.
        seedPriorIncidents(run2.incidents, 80);

        // This session (post-restart): process 4 alarms forming 2 incidents.
        run2.feed(alarm("r2a", "LOS"), "T", T0 + 100);
        run2.feed(alarm("r2b", "LinkDown"), "T", T0 + 101); // incident 1
        run2.feed(alarm("r2c", "LOS"), "T", T0 + 102);
        run2.feed(alarm("r2d", "LinkDown"), "T", T0 + 103); // incident 2

        StatsView stats =
                new StatsAggregator(run2.incidents, run2.engine, RcaAccuracyOracle.DISABLED).snapshot();

        assertThat(stats.totalAlarmsProcessed()).isEqualTo(4);
        // NOT the 80+2 all-time DB incidents — the engine's 2 session incidents.
        assertThat(stats.totalIncidentsCreated()).isEqualTo(2);
        double alarmReduction =
                (double) stats.totalAlarmsProcessed() / stats.totalIncidentsCreated();
        assertThat(alarmReduction).isGreaterThanOrEqualTo(1.0);
    }

    /**
     * Seeds {@code correlatedAlarmCount} distinct correlated alarms into the repository as prior-run
     * incident history — the "all-time DB" shape that, if (wrongly) used as the {@code /stats}
     * numerator, would blow the rate past 100%.
     */
    private static void seedPriorHistory(IncidentRepository repository, int distinctCorrelatedAlarms) {
        for (int i = 0; i < distinctCorrelatedAlarms; i += 2) {
            String root = "hist-root-" + i;
            String child = "hist-child-" + i;
            repository.save(com.acp.correlationengine.support.Fixtures.incident(
                    "HIST-" + i, "T", root, "LOS", List.of(child)));
        }
    }

    /**
     * Seeds {@code count} prior-run incidents into the repository (the all-time DB shape). Used to
     * prove the alarm-reduction denominator ({@code totalIncidentsCreated}) is the engine's session
     * count, not this stale all-time DB total.
     */
    private static void seedPriorIncidents(IncidentRepository repository, int count) {
        for (int i = 0; i < count; i++) {
            repository.save(com.acp.correlationengine.support.Fixtures.incident(
                    "PRIOR-" + i, "T", "prior-root-" + i, "LOS", List.of("prior-child-" + i)));
        }
    }
}
