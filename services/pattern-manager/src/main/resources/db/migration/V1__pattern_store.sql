-- Pattern Store schema (owned solely by the Pattern Manager). Logical schema `pattern`.
-- Flyway applies this on startup. No other service writes to this schema.

CREATE SCHEMA IF NOT EXISTS pattern;

-- The enriched, governed pattern record. patternId is a deterministic UUIDv5 over the mining
-- provenance (upsert idempotency). sessionWindow is derived once at intake (read-only in MVP) and
-- IS carried on the frozen events; structural-validation flag/reason + edit_meta are INTERNAL only.
CREATE TABLE pattern.pattern (
    pattern_id                    UUID PRIMARY KEY,
    trail_id                      TEXT NOT NULL,
    root_cause_alarm_type         TEXT NOT NULL,
    support                       DOUBLE PRECISION NOT NULL,
    confidence                    DOUBLE PRECISION NOT NULL,
    lift                          DOUBLE PRECISION NOT NULL,
    timing                        JSONB NOT NULL,
    codebook_match_id             TEXT NULL,
    reconcile_status              TEXT NOT NULL
                                    CHECK (reconcile_status IN ('confirmed', 'merged', 'unexplained')),
    structurally_validated        BOOLEAN NOT NULL,
    structural_validation_reason  TEXT NULL,
    session_window_ms             BIGINT NOT NULL CHECK (session_window_ms > 0),
    session_window_type           TEXT NOT NULL CHECK (session_window_type IN ('gap-based', 'fixed')),
    instance_count                INT NOT NULL CHECK (instance_count > 0),
    lifecycle                     TEXT NOT NULL DEFAULT 'draft'
                                    CHECK (lifecycle IN ('draft', 'approved', 'deprecated', 'rejected')),
    domain                        TEXT NULL,
    edit_meta                     JSONB NULL,
    created_at                    TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL,
    -- A reason is always present exactly when the pattern is NOT structurally validated.
    CONSTRAINT chk_structural_reason
        CHECK (structurally_validated = TRUE OR structural_validation_reason IS NOT NULL)
);

CREATE INDEX idx_pattern_lifecycle ON pattern.pattern (lifecycle);
CREATE INDEX idx_pattern_structval ON pattern.pattern (structurally_validated);

-- Ordered alarm-type sequence; the `optional` marker is the operator-edit placeholder (internal).
CREATE TABLE pattern.sequence_element (
    id          UUID PRIMARY KEY,
    pattern_id  UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    position    INT NOT NULL,
    alarm_type  TEXT NOT NULL,
    optional    BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (pattern_id, position)
);

-- Example occurrences from the Miner provenance (may be zero rows).
CREATE TABLE pattern.supporting_instance (
    id               UUID PRIMARY KEY,
    pattern_id       UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    source_window_id TEXT NULL,
    snapshot_id      TEXT NULL,
    occurrence       JSONB NULL
);

-- Auditable lifecycle transitions (one row per state change, non-null timestamp).
CREATE TABLE pattern.lifecycle_transition (
    id              UUID PRIMARY KEY,
    pattern_id      UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    from_state      TEXT NOT NULL,
    to_state        TEXT NOT NULL,
    reviewer        TEXT NULL,
    notes           TEXT NULL,
    transitioned_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_transition_pattern ON pattern.lifecycle_transition (pattern_id);

-- The eventId idempotency dedupe set (written in the same tx as the pattern upsert).
CREATE TABLE pattern.processed_event (
    event_id     UUID PRIMARY KEY,
    source       TEXT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    pattern_id   UUID NULL
);
