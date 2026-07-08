package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.ResetResult;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository.PurgeCounts;
import com.acp.correlationengine.observability.CorrelationMetrics;

/**
 * Orchestrates the P3 demo/ops correlation reset behind {@code POST /admin/reset-correlation}: purge
 * the CE-owned incident tables (transactional) and reset the in-memory correlation session so
 * {@code /stats} KPIs return to zero.
 *
 * <p><b>P3-only scope.</b> This clears only the state the Correlation Engine owns for the live
 * correlation path — the persisted incidents ({@code incident.incident} +
 * {@code incident.incident_alarm}) and the engine's session-scoped in-memory registries/counters. It
 * does NOT touch the loaded P2 model: the compatibility index / approved patterns / codebook /
 * Knowledge params (the {@code StartupSnapshotDiscovery} result) survive, so a fresh alarm after the
 * reset still correlates without a CE restart. It also does NOT clear
 * {@code incident.processed_event} — that ledger dedupes the P2 model events
 * ({@code patterns.approved} / {@code codebook.generated} / {@code trails.built}) whose
 * {@code eventId}s must survive.
 *
 * <p><b>Idempotent.</b> A second reset with nothing left to purge returns zero counts and
 * {@code resetInMemory=true}; no error.
 */
public class CorrelationResetService {

    private final IncidentRepository incidentRepository;
    private final CorrelationEngine engine;
    private final CorrelationMetrics metrics;

    public CorrelationResetService(IncidentRepository incidentRepository, CorrelationEngine engine,
            CorrelationMetrics metrics) {
        this.incidentRepository = incidentRepository;
        this.engine = engine;
        this.metrics = metrics;
    }

    /**
     * Purge incidents then reset the in-memory session. The DB delete is a transactional repository
     * operation; the in-memory reset is synchronized on the same monitor as {@code onAlarm}, so no
     * concurrent correlation step observes half-cleared state.
     *
     * @return the purge counts + in-memory-reset confirmation
     */
    public ResetResult reset() {
        PurgeCounts purged = incidentRepository.deleteAll();
        engine.reset();
        metrics.incrementCorrelationReset();
        return new ResetResult(purged.purgedIncidents(), purged.purgedIncidentAlarms(), true);
    }
}
