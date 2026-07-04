-- noise-filter observed-noise / chatter signature store (DA-16, idempotent).
-- depends: 0001_run_stats

CREATE TABLE IF NOT EXISTS noise_filter.nf_observed_chatter (
    id                  BIGSERIAL       PRIMARY KEY,
    managed_object_id   TEXT            NULL,           -- part of the chatter key; null => source-level chatter
    alarm_type          TEXT            NOT NULL,       -- canonical Knowledge alarmTypeVocabulary token
    event_type          TEXT            NOT NULL,
    trail_id            TEXT            NULL,            -- the trail the noise was observed on (context only)
    occurrence_count    BIGINT          NOT NULL DEFAULT 1 CHECK (occurrence_count >= 1),
    first_seen          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    last_seen           TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- the upsert key: one row per distinct (managed_object_id, alarm_type, event_type, trail_id).
-- two partial unique indexes so a NULL managed_object_id still has a well-defined key.
CREATE UNIQUE INDEX IF NOT EXISTS ux_nf_chatter_with_mo
    ON noise_filter.nf_observed_chatter (managed_object_id, alarm_type, event_type, trail_id)
    WHERE managed_object_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_nf_chatter_no_mo
    ON noise_filter.nf_observed_chatter (alarm_type, event_type, trail_id)
    WHERE managed_object_id IS NULL;

-- ranking index for the read endpoint (most-frequent noise first)
CREATE INDEX IF NOT EXISTS ix_nf_chatter_occurrence
    ON noise_filter.nf_observed_chatter (occurrence_count DESC);
