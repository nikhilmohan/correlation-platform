package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import java.util.List;

/**
 * Config-switchable client for the Pattern Manager read API {@code GET /patterns?lifecycle=approved}
 * (the {@code PatternPage} envelope of {@code PatternView} items). Called at startup and on every
 * {@code patterns.approved} event (the event is the refresh trigger; this read API is the SOURCE of
 * {@code trailId} for {@code (trailId, patternId)} keying — AC27). Built against Pattern Manager's
 * published OpenAPI; mock in unit tests, real in integration.
 */
public interface PatternManagerClient {

    /**
     * @return all currently approved patterns, each with its {@code trailId} (from
     *     {@code PatternView.trailId}) and per-pattern {@code sessionWindow}.
     */
    List<PatternRef> listApproved();
}
