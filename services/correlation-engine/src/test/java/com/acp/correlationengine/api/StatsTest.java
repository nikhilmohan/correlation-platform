package com.acp.correlationengine.api;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.api.StatsAggregator.RcaAccuracyOracle;
import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Stats read-API acceptance criteria (AC17 alarm-reduction ratio, AC28 auto-correlation fraction,
 * D2 rcaAccuracy eval-mode). Drives the real engine so the counts come from actual correlation.
 */
class StatsTest {

    /** AC17 — alarm-reduction ratio derivable: totalAlarmsProcessed >= K, totalIncidentsCreated = I. */
    @Test
    void ac17_alarmReductionRatioDerivable() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // K = 4 raw alarms collapsing to I = 2 incidents
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1); // -> incident 1
        h.feed(alarm("a3", "LOS"), "T", T0 + 2);
        h.feed(alarm("a4", "LinkDown"), "T", T0 + 3); // -> incident 2

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        StatsView stats = agg.snapshot();

        assertThat(stats.totalAlarmsProcessed()).isGreaterThanOrEqualTo(4);
        assertThat(stats.totalIncidentsCreated()).isEqualTo(2);
        // reduction ratio K/I derivable from the response alone
    }

    /** AC28 — auto-correlation fraction: correlatedAlarmCount / totalAlarmsProcessed derivable. */
    @Test
    void ac28_autoCorrelationFractionDerivable() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // C = 2 distinct alarms placed into a correlated incident; plus 1 uncorrelated noise alarm.
        h.feed(alarm("root", "LOS"), "T", T0);
        h.feed(alarm("child", "LinkDown"), "T", T0 + 1); // -> 1 incident, 2 correlated alarms
        h.feed(alarm("noise", "CardFault"), "T", T0 + 2); // no pattern/codebook — uncorrelated

        StatsAggregator agg = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        StatsView stats = agg.snapshot();

        assertThat(stats.totalAlarmsProcessed()).isEqualTo(3);
        assertThat(stats.correlatedAlarmCount()).isEqualTo(2); // distinct alarmIds in incidents
        // auto-correlation rate = 2/3, distinct from the alarm-reduction ratio
        double autoCorrelation = (double) stats.correlatedAlarmCount() / stats.totalAlarmsProcessed();
        assertThat(autoCorrelation).isEqualTo(2.0 / 3.0);
    }

    /** D2 — rcaAccuracy is null in production; populated when an eval-mode oracle is wired. */
    @Test
    void rcaAccuracy_nullInProduction_populatedInEvalMode() {
        EngineHarness h = new EngineHarness();
        StatsAggregator prod = new StatsAggregator(h.incidents, h.engine, RcaAccuracyOracle.DISABLED);
        assertThat(prod.snapshot().rcaAccuracy()).isNull();

        RcaAccuracyOracle wired = () -> Optional.of(0.83);
        StatsAggregator eval = new StatsAggregator(h.incidents, h.engine, wired);
        assertThat(eval.snapshot().rcaAccuracy()).isEqualTo(0.83);
    }
}
