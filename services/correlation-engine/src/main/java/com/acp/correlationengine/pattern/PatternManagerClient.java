package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import java.util.List;
import java.util.Optional;

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

    /**
     * Fallback snapshot-discovery source for startup (used when the Topology Service is unreachable):
     * the topology snapshot an approved pattern was mined against, read off
     * {@code PatternView.supportingInstances[].snapshotId} (the top-level {@code PatternView.snapshotId}
     * is null; the snapshot lives on the supporting instances). Returns the first non-blank snapshotId
     * found across the approved page.
     *
     * @return a current-enough snapshot id, or {@link Optional#empty()} if none can be derived.
     */
    Optional<String> discoverSnapshotId();
}
