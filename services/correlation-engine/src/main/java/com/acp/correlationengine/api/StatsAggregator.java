package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.incident.IncidentRepository;

/**
 * Assembles {@link StatsView} for {@code GET /stats}.
 *
 * <p><b>Scope consistency — every count that feeds a RATIO is engine-session-scoped.</b> Two ratios
 * are displayed and each must be internally consistent (never exceed its natural bound):
 * <ul>
 *   <li>auto-correlation = {@code correlatedAlarmCount / totalAlarmsProcessed} — must be {@code <= 1};
 *   <li>alarm-reduction = {@code totalAlarmsProcessed / totalIncidentsCreated} — must be {@code >= 1}
 *       (each incident consumes at least one alarm).
 * </ul>
 * All FOUR counts that participate in these ratios are sourced from the engine's own session-scoped
 * in-memory state, which share ONE lifetime and reset together on restart:
 * <ul>
 *   <li>{@code totalAlarmsProcessed} = {@link CorrelationEngine#totalAlarmsProcessed()} — distinct
 *       alarmIds ingested this session (auto-corr denominator, alarm-reduction numerator);
 *   <li>{@code correlatedAlarmCount} = {@link CorrelationEngine#correlatedAlarmCount()} — distinct
 *       alarmIds correlated into an incident this session (auto-corr numerator);
 *   <li>{@code totalIncidentsCreated} = {@link CorrelationEngine#totalIncidentsCreated()} — distinct
 *       incidents fired this session (alarm-reduction denominator).
 * </ul>
 * Because every correlated alarm was ingested first, and every fired incident consumes at least its
 * root-cause alarm, {@code correlatedAlarmCount <= totalAlarmsProcessed} and
 * {@code totalIncidentsCreated <= totalAlarmsProcessed} both hold structurally — so auto-correlation
 * stays in {@code [0, 1]} and alarm-reduction stays {@code >= 1}. These are not defensive clamps; the
 * counts are same-scope so the ratios are genuinely consistent.
 *
 * <p><b>Why NOT the DB counts.</b> Sourcing any ratio count from the IncidentRepository yields an
 * ALL-TIME count over the persistent Incident Store. Paired with the since-restart in-memory
 * counterpart it produces impossible numbers: the &gt;100% auto-correlation observed live
 * (279 all-time correlated / 181 since-restart processed = 154%), and — the sibling bug fixed here —
 * an alarm-reduction below 1.0 when the all-time incident count exceeds the since-restart processed
 * count after a restart or on a larger DB.
 *
 * <p>{@code patternMatchCount} / {@code codebookMatchCount} are ALSO taken session-scoped from the
 * engine ({@link CorrelationEngine#patternMatchCount()} / {@link CorrelationEngine#codebookMatchCount()})
 * so their sum equals {@code totalIncidentsCreated} (same scope, no cross-scope surprise if a consumer
 * derives a share). {@code confidenceDistribution} is the only count left DB-derived: it is purely
 * informational (a per-band histogram), participates in no ratio, and the design intends it as a view
 * over persisted incidents. {@code rcaAccuracy} is {@code null} unless eval-mode is on and a labels
 * oracle is wired (D2).
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
                engine.totalIncidentsCreated(),
                engine.patternMatchCount(),
                engine.codebookMatchCount(),
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
