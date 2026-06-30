-- noise-filter run-stats schema (DA-15, idempotent — yoyo applies at startup).
-- Single-owner schema `noise_filter` per the architecture shared-infra schema-ownership list.
-- depends:

CREATE SCHEMA IF NOT EXISTS noise_filter;

CREATE TABLE IF NOT EXISTS noise_filter.nf_run_stats (
    run_id                  UUID            PRIMARY KEY,
    run_timestamp           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    trail_id                TEXT            NOT NULL,
    snapshot_id             TEXT            NOT NULL,
    domain                  TEXT            NULL,           -- optional; null => single MVP domain
    window_start            TIMESTAMPTZ     NOT NULL,
    window_end              TIMESTAMPTZ     NOT NULL,
    -- DBSCAN params actually used for this execution (Knowledge-sourced; never hard-coded)
    eps                     DOUBLE PRECISION NOT NULL,
    min_samples             INTEGER         NOT NULL,
    window_size_seconds     INTEGER         NOT NULL,
    algorithm               TEXT            NOT NULL,       -- dbscan | hdbscan
    -- aggregate counts for this execution
    alarms_in               INTEGER         NOT NULL CHECK (alarms_in >= 0),
    clusters_formed         INTEGER         NOT NULL CHECK (clusters_formed >= 0),
    alarms_kept             INTEGER         NOT NULL CHECK (alarms_kept >= 0),
    alarms_dropped          INTEGER         NOT NULL CHECK (alarms_dropped >= 0),
    noise_ratio             DOUBLE PRECISION NOT NULL,      -- alarms_dropped / alarms_in (0 when alarms_in = 0)
    -- storm-scale + retention stats (nullable: only meaningful for some runs)
    storm_max_cluster_size  INTEGER         NULL,
    storm_reduction_ratio   DOUBLE PRECISION NULL,          -- alarms_in / clusters_formed (null when clusters_formed = 0)
    retention_vs_oracle     DOUBLE PRECISION NULL,          -- kept_valid / oracle_valid, when an oracle label is available
    hop_feature_enabled     BOOLEAN         NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS ix_nf_run_stats_trail_time
    ON noise_filter.nf_run_stats (trail_id, run_timestamp DESC);
CREATE INDEX IF NOT EXISTS ix_nf_run_stats_time
    ON noise_filter.nf_run_stats (run_timestamp DESC);
