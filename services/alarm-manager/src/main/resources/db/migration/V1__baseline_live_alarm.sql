-- migration V1__baseline_live_alarm.sql
-- Owned schema (idempotent); all Alarm Manager tables live here.
CREATE SCHEMA IF NOT EXISTS live_alarm;

-- Base live-alarm record (one row per alarm; alarm_id is the idempotency anchor).
CREATE TABLE live_alarm.alarm (
  alarm_id           text        PRIMARY KEY,
  managed_object_id  text        NOT NULL,
  event_type         text        NOT NULL,
  probable_cause     text        NOT NULL,
  perceived_severity text        NOT NULL,
  wire_state         text        NOT NULL,
  raised_at          timestamptz NOT NULL,
  cleared_at         timestamptz,
  trail_ids          jsonb       NOT NULL,
  vendor_raw         jsonb,
  lifecycle_state    text        NOT NULL,
  role               text        NOT NULL DEFAULT 'none',
  incident_id        text,
  published          boolean     NOT NULL DEFAULT false,
  raw_envelope       jsonb       NOT NULL,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  CONSTRAINT alarm_lifecycle_state_chk
    CHECK (lifecycle_state IN ('open', 'correlated', 'cleared'))
);

-- Append-only audit (one row per lifecycle/role change).
CREATE TABLE live_alarm.state_transition (
  id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alarm_id           text        NOT NULL REFERENCES live_alarm.alarm (alarm_id),
  to_state           text        NOT NULL,
  reason             text,
  caused_by_event_id text,
  occurred_at        timestamptz NOT NULL
);

-- Shared idempotency guard for the event-driven channels.
CREATE TABLE live_alarm.processed_event (
  event_id   text        PRIMARY KEY,
  applied_at timestamptz NOT NULL
);

-- Base indexes / constraints.
CREATE INDEX idx_alarm_lifecycle_state ON live_alarm.alarm (lifecycle_state);
CREATE INDEX idx_alarm_incident_id     ON live_alarm.alarm (incident_id);
CREATE INDEX idx_alarm_raised_at       ON live_alarm.alarm (raised_at);
CREATE INDEX gin_alarm_trail_ids       ON live_alarm.alarm USING gin (trail_ids);
CREATE INDEX idx_transition_alarm_id   ON live_alarm.state_transition (alarm_id, occurred_at);
-- At most one ingest-origin 'open' audit row per alarm (acceptance #1); a later
-- revert-to-open carries a different reason, so it is excluded from the partial unique guard.
CREATE UNIQUE INDEX uq_transition_open_ingest
  ON live_alarm.state_transition (alarm_id)
  WHERE to_state = 'open' AND reason = 'ingest';
