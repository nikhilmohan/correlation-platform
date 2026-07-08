package com.acp.correlationengine.api;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.api.StatsAggregator.RcaAccuracyOracle;
import com.acp.correlationengine.api.dto.ResetResult;
import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.incident.IncidentRepository.IncidentFilter;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * P3 demo/ops reset acceptance criteria (new): {@code POST /admin/reset-correlation} purges the
 * CE-owned incidents and resets the in-memory correlation session, while the loaded P2 model
 * survives so a subsequent run still correlates. Drives the REAL engine (as {@link StatsTest} does)
 * so the counts and correlation come from actual correlation, not stubs.
 */
class CorrelationResetServiceTest {

    private static final IncidentFilter ALL =
            new IncidentFilter(null, null, null, null, 500, 0);

    /**
     * After correlating some alarms → incidents, reset zeroes ALL /stats counters, empties the
     * incident repo AND the in-memory instance registry, and reports the purge counts. Crucially the
     * compatibility index (approved patterns) is STILL loaded, so a subsequent onAlarm still
     * correlates — proving the P2 model survived.
     */
    @Test
    void reset_zeroesStatsAndPurgesIncidents_butP2ModelSurvives() {
        EngineHarness h = new EngineHarness();
        PatternRef pattern = gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000);
        h.addPattern(pattern);

        // Two cascades collapse to two incidents (4 alarms processed, 4 correlated).
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1);
        h.feed(alarm("a3", "LOS"), "T", T0 + 2);
        h.feed(alarm("a4", "LinkDown"), "T", T0 + 3);

        // Leave one instance open (LOS with no closing LinkDown) so the registry is non-empty.
        h.feed(alarm("a5", "LOS"), "T", T0 + 4);

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        assertThat(agg.snapshot().totalIncidentsCreated()).isEqualTo(2);
        assertThat(h.incidents.count(ALL)).isEqualTo(2);
        assertThat(h.engine.activeInstanceCount()).isGreaterThan(0);
        // P2 model loaded before reset.
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T")).isNotEmpty();

        CorrelationResetService reset =
                new CorrelationResetService(h.incidents, h.engine, CorrelationMetrics.NOOP);
        ResetResult result = reset.reset();

        // Response shape: purge counts + in-memory reset flag.
        assertThat(result.purgedIncidents()).isEqualTo(2);
        // 2 incidents each with 1 root-cause + 1 child membership row = 4 alarm rows.
        assertThat(result.purgedIncidentAlarms()).isEqualTo(4);
        assertThat(result.resetInMemory()).isTrue();

        // All /stats KPIs back to 0.
        StatsView after = agg.snapshot();
        assertThat(after.totalAlarmsProcessed()).isZero();
        assertThat(after.correlatedAlarmCount()).isZero();
        assertThat(after.totalIncidentsCreated()).isZero();
        assertThat(after.patternMatchCount()).isZero();
        assertThat(after.codebookMatchCount()).isZero();

        // Incident repo emptied, in-memory registry emptied.
        assertThat(h.incidents.count(ALL)).isZero();
        assertThat(h.incidents.totalIncidents()).isZero();
        assertThat(h.engine.activeInstanceCount()).isZero();

        // P2 model STILL loaded — the compatibility index / approved patterns survived the reset.
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T")).isNotEmpty();

        // Proof: a fresh cascade after the reset still correlates without any reload/restart.
        h.feed(alarm("b1", "LOS"), "T", T0 + 100);
        h.feed(alarm("b2", "LinkDown"), "T", T0 + 101);
        StatsView afterRun = agg.snapshot();
        assertThat(afterRun.totalIncidentsCreated()).isEqualTo(1);
        assertThat(afterRun.correlatedAlarmCount()).isEqualTo(2);
        assertThat(h.incidents.count(ALL)).isEqualTo(1);
    }

    /** Idempotent: a second reset with nothing left to purge returns zeros + resetInMemory, no error. */
    @Test
    void reset_isIdempotent_secondCallReturnsZeros() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1);

        CorrelationResetService reset =
                new CorrelationResetService(h.incidents, h.engine, CorrelationMetrics.NOOP);

        ResetResult first = reset.reset();
        assertThat(first.purgedIncidents()).isEqualTo(1);

        ResetResult second = reset.reset();
        assertThat(second.purgedIncidents()).isZero();
        assertThat(second.purgedIncidentAlarms()).isZero();
        assertThat(second.resetInMemory()).isTrue();
    }

    /** The reset ticks the {@code correlation_reset_total} metric. */
    @Test
    void reset_incrementsResetMetric() {
        EngineHarness h = new EngineHarness();
        CountingMetrics metrics = new CountingMetrics();
        CorrelationResetService reset = new CorrelationResetService(h.incidents, h.engine, metrics);

        reset.reset();
        reset.reset();

        assertThat(metrics.resetCount).isEqualTo(2);
    }

    /**
     * Thread-safety: a reset interleaved with a burst of {@code onAlarm} calls never corrupts state —
     * because {@link com.acp.correlationengine.correlate.CorrelationEngine#reset()} synchronizes on
     * the same monitor as {@code onAlarm}, the reset is atomic wrt each alarm step. After the burst we
     * clear once more with no concurrency, then assert the mid-session counters are consistently zero
     * (never a half-cleared negative/garbage state).
     */
    @Test
    void reset_isThreadSafeWithConcurrentOnAlarm() throws Exception {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));
        CorrelationResetService reset =
                new CorrelationResetService(h.incidents, h.engine, CorrelationMetrics.NOOP);

        int alarms = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean corrupted = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < alarms; i++) {
                String type = (i % 2 == 0) ? "LOS" : "LinkDown";
                h.feed(alarm("x" + i, type), "T", T0 + i);
            }
        });
        Thread resetter = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < 50; i++) {
                reset.reset();
                // Under the shared lock, counts are always internally consistent (non-negative,
                // correlated <= processed). A corrupt interleave would break these invariants.
                long processed = h.engine.totalAlarmsProcessed();
                long correlated = h.engine.correlatedAlarmCount();
                if (processed < 0 || correlated < 0 || correlated > processed) {
                    corrupted.set(true);
                }
            }
        });

        producer.start();
        resetter.start();
        start.countDown();
        producer.join(TimeUnit.SECONDS.toMillis(10));
        resetter.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(corrupted).as("reset never observed half-cleared/corrupt state").isFalse();

        // Final clean reset with no concurrency → fully zeroed.
        reset.reset();
        assertThat(h.engine.totalAlarmsProcessed()).isZero();
        assertThat(h.engine.correlatedAlarmCount()).isZero();
        assertThat(h.engine.totalIncidentsCreated()).isZero();
        assertThat(h.engine.activeInstanceCount()).isZero();
        assertThat(h.incidents.count(ALL)).isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal counting metrics double for the reset counter assertion. */
    private static final class CountingMetrics implements CorrelationMetrics {
        int resetCount = 0;

        @Override public void incrementCorrelationReset() {
            resetCount++;
        }

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
    }
}
