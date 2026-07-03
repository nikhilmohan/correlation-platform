-- [ANCHOR-CONSOL] Anchor-identity consolidation (P2 over-count fix).
--
-- The Pattern Miner now emits a P2 corpus across multiple bounded sub-runs, so the SAME
-- fault-origin (same provenance.anchorScenarioId) can produce more than one PatternMinedEvent.
-- Anchored mined events sharing one (domain, snapshotId, codebookVersion, anchorScenarioId) now
-- consolidate into ONE pattern.pattern row whose occurrences/support/timing are running aggregates.
--
-- This migration is ADDITIVE (never edits V1): it adds the anchor-identity columns to pattern and
-- introduces the contributing_event child table that makes the fold idempotent + order-independent.

-- Anchor-identity columns on the pattern row. Nullable: unexplained patterns (anchorScenarioId
-- null/absent) keep the per-event identity and never consolidate by anchor. snapshot_id /
-- codebook_version scope the identity so a new topology snapshot or recompiled codebook re-mints it.
ALTER TABLE pattern.pattern ADD COLUMN anchor_scenario_id TEXT NULL;
ALTER TABLE pattern.pattern ADD COLUMN snapshot_id        TEXT NULL;
ALTER TABLE pattern.pattern ADD COLUMN codebook_version   TEXT NULL;

-- Internal aggregation-support column: the occurrence-weighted support (support * occurrences) of
-- the contributor that currently owns the representative sequence. Used ONLY to decide, on a later
-- fold, whether the new contributor should replace the representative (highest weighted support,
-- tie-broken longest then lexicographic). Never served on the API or events.
ALTER TABLE pattern.pattern ADD COLUMN representative_weight DOUBLE PRECISION NULL;

-- Surfacing flagged/anchored patterns in review + supporting the fold lookup by anchor.
CREATE INDEX idx_pattern_anchor ON pattern.pattern (anchor_scenario_id);

-- The set of mined eventIds already folded into an anchored pattern. One row per contributing
-- eventId (PRIMARY KEY event_id). The consolidation fold does an
--   INSERT ... ON CONFLICT (event_id) DO NOTHING
-- BEFORE aggregating, so a re-delivered / replayed mined event whose eventId is already present is
-- NOT folded again (no double-count) — complementing the processed_event gate. Because occurrences
-- are a SUM over this DISTINCT set and each eventId contributes at most once, the aggregate is a
-- deterministic function of the set, independent of arrival order.
CREATE TABLE pattern.contributing_event (
    event_id           UUID PRIMARY KEY,
    pattern_id         UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    anchor_scenario_id TEXT NULL,
    occurrences        INT NOT NULL,
    support            DOUBLE PRECISION NOT NULL,
    folded_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_contrib_pattern ON pattern.contributing_event (pattern_id);
