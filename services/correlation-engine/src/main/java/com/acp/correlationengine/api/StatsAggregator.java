package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.StatsView;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.incident.IncidentRepository;

/**
 * Assembles {@link StatsView} for {@code GET /stats}. {@code totalAlarmsProcessed} is the engine's
 * distinct-ingest count; the incident-derived counts ({@code totalIncidentsCreated},
 * {@code patternMatchCount}, {@code codebookMatchCount}, {@code correlatedAlarmCount},
 * {@code confidenceDistribution}) come from the owned Incident Store — so both numerator and
 * denominator of the auto-correlation rate ({@code correlatedAlarmCount / totalAlarmsProcessed})
 * are self-contained and reproducible (D1). {@code rcaAccuracy} is {@code null} unless eval-mode is
 * on and a labels oracle is wired (D2).
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
                repository.distinctCorrelatedAlarmCount(),
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
