package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.incident.IncidentRepository;

/**
 * Assembles {@link StatsView} for {@code GET /stats}.
 *
 * <p><b>Scope consistency (auto-correlation rate).</b> Both the numerator and the denominator of the
 * auto-correlation rate ({@code correlatedAlarmCount / totalAlarmsProcessed}) MUST come from the same
 * scope, else the ratio is meaningless. They are both sourced from the engine's own session-scoped
 * in-memory state: {@code totalAlarmsProcessed} = {@link CorrelationEngine#totalAlarmsProcessed()}
 * (distinct alarmIds ingested this session) and {@code correlatedAlarmCount} =
 * {@link CorrelationEngine#correlatedAlarmCount()} (distinct alarmIds correlated into an incident this
 * session). These two counters share one lifetime and reset together on restart, and every correlated
 * alarm was necessarily ingested first — so {@code correlatedAlarmCount <= totalAlarmsProcessed}
 * always holds and the rate stays in {@code [0, 1]}.
 *
 * <p>The numerator deliberately does NOT read {@code repository.distinctCorrelatedAlarmCount()}: that
 * is an ALL-TIME count over the persistent Incident Store, which — paired with the since-restart
 * in-memory denominator — produced the impossible &gt;100% auto-correlation rate observed live
 * (279 all-time correlated / 181 since-restart processed = 154%).
 *
 * <p>The remaining counts ({@code totalIncidentsCreated}, {@code patternMatchCount},
 * {@code codebookMatchCount}, {@code confidenceDistribution}) stay incident-derived — they are
 * standalone all-time counts used for the alarm-reduction view, not for the auto-correlation rate.
 * {@code rcaAccuracy} is {@code null} unless eval-mode is on and a labels oracle is wired (D2).
 */
public class StatsAggregator {

    private final IncidentRepository repository;
    private final CorrelationEngine engine;
    private final RcaAccuracyOracle rcaOracle;

    public StatsAggregator(IncidentRepository repository, CorrelationEngine engine,
            RcaAccuracyOracle rcaOracle) {
        this.repository = repository;
        this.engine = engine;
        this.rcaOracle = rcaOracle;
    }

    public StatsView snapshot() {
        return new StatsView(
                engine.totalAlarmsProcessed(),
                engine.correlatedAlarmCount(),
                repository.totalIncidents(),
                repository.countByMatchType("pattern"),
                repository.countByMatchType("codebook"),
                repository.confidenceDistribution(),
                rcaOracle.accuracy().orElse(null));
    }

    /**
     * Eval-mode-only RCA-accuracy source. In production ({@code RCA_EVAL_MODE=off}) the default
     * implementation returns empty, so {@code GET /stats.rcaAccuracy} is {@code null} — the engine
     * owns no ground truth at runtime (D2).
     */
    public interface RcaAccuracyOracle {
        java.util.Optional<Double> accuracy();

        RcaAccuracyOracle DISABLED = java.util.Optional::empty;
    }
}
