-- V2 — the Incident Store tables, all schema-qualified into `incident`.
-- Naming note: the schema is `incident` and one of its tables is also `incident`; the
-- system-of-record table is therefore always `incident.incident`.

CREATE TABLE IF NOT EXISTS incident.incident (
    incident_id           text        PRIMARY KEY,
    trail_id              text        NOT NULL,
    root_cause_alarm_id   text        NOT NULL,
    root_cause_alarm_type text        NOT NULL,
    matched_pattern_id    text        NULL,
    matched_codebook_id   text        NULL,
    confidence            numeric(5,4) NOT NULL,
    match_type            text        NOT NULL,
    instance_fingerprint  text        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_incident_fingerprint UNIQUE (instance_fingerprint)
);

CREATE INDEX IF NOT EXISTS ix_incident_trail_id   ON incident.incident (trail_id);
CREATE INDEX IF NOT EXISTS ix_incident_created_at ON incident.incident (created_at);
CREATE INDEX IF NOT EXISTS ix_incident_match_type ON incident.incident (match_type);

CREATE TABLE IF NOT EXISTS incident.incident_alarm (
    id          bigserial PRIMARY KEY,
    incident_id text      NOT NULL
        REFERENCES incident.incident (incident_id) ON DELETE CASCADE,
    alarm_id    text      NOT NULL,
    role        text      NOT NULL,
    CONSTRAINT uq_incident_alarm UNIQUE (incident_id, alarm_id)
);

CREATE INDEX IF NOT EXISTS ix_incident_alarm_alarm_id ON incident.incident_alarm (alarm_id);

-- Idempotency ledger for consumed events deduped on eventId (patterns.approved / codebook.generated).
CREATE TABLE IF NOT EXISTS incident.processed_event (
    dedupe_key   text        PRIMARY KEY,
    scope        text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
