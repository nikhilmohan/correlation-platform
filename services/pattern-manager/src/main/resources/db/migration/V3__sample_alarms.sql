-- V3__sample_alarms.sql
-- A bounded, representative sample of the real member alarms a pattern was mined from
-- (operator XAI / trust). Sourced from PatternMinedEvent.sampleAlarms[] (already frozen on main).
-- Mirrors pattern.supporting_instance: surrogate PK, pattern_id FK ON DELETE CASCADE. Additive only;
-- never edits V1/V2. Zero rows when the mined event carried no sampleAlarms (backward-compat).
CREATE TABLE pattern.sample_alarm (
    id                 UUID PRIMARY KEY,
    pattern_id         UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    alarm_id           TEXT NOT NULL,
    alarm_type         TEXT NOT NULL,
    raised_at          TIMESTAMPTZ NOT NULL,
    managed_object_id  TEXT NOT NULL,
    perceived_severity TEXT NOT NULL,
    position           INT NOT NULL,           -- deterministic serve order (miner sample order)
    UNIQUE (pattern_id, position)
);

CREATE INDEX idx_sample_alarm_pattern ON pattern.sample_alarm (pattern_id);
