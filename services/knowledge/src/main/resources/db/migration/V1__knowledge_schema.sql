-- Knowledge Service — schema-scoped migration.
-- Owns ONLY the `knowledge` logical schema; touches no other schema (single-owner invariant).
-- Flyway is configured (application.yml) with schemas=knowledge and its own
-- knowledge.flyway_schema_history table.

CREATE SCHEMA IF NOT EXISTS knowledge;

-- Stable identity of a knowledge record (one row per logical record).
CREATE TABLE IF NOT EXISTS knowledge.record (
    domain        TEXT NOT NULL,
    record_type   TEXT NOT NULL,
    record_id     TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (domain, record_type, record_id)
);

-- Append-only versions. Each successful write inserts a new row and flips is_current.
CREATE TABLE IF NOT EXISTS knowledge.record_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    domain        TEXT NOT NULL,
    record_type   TEXT NOT NULL,
    record_id     TEXT NOT NULL,
    version       TEXT NOT NULL,
    is_current    BOOLEAN NOT NULL DEFAULT TRUE,
    payload       JSONB NOT NULL,
    author        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (domain, record_type, record_id)
        REFERENCES knowledge.record (domain, record_type, record_id),
    UNIQUE (domain, record_type, record_id, version)
);

-- Exactly one current version per record (partial unique index).
CREATE UNIQUE INDEX IF NOT EXISTS uq_record_current
    ON knowledge.record_version (domain, record_type, record_id)
    WHERE is_current;

-- Fast domain-scoped + type-scoped current reads (the consumer read path).
CREATE INDEX IF NOT EXISTS ix_version_domain_type_current
    ON knowledge.record_version (domain, record_type)
    WHERE is_current;
