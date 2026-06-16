-- Codebook Store schema (owned schema: codebook). Applied by yoyo-migrations at container
-- startup, before the consumer/API start. Idempotent (IF NOT EXISTS + yoyo's applied-migration
-- ledger) so restarts are no-ops. ALL DDL is schema-qualified to `codebook` so nothing lands
-- in the shared PostgreSQL `public` schema.

-- Step 0: idempotent schema creation (nothing lands in public)
CREATE SCHEMA IF NOT EXISTS codebook;

-- Step 1: codebooks
CREATE TABLE IF NOT EXISTS codebook.codebooks (
    codebook_id        text        PRIMARY KEY,            -- freshly minted per compilation (cb-{uuid4})
    snapshot_id        text        NOT NULL,               -- from the triggering trails.built
    domain             text        NOT NULL,               -- first-class; resolved domain (default core-ip)
    active             boolean     NOT NULL DEFAULT true,  -- single active codebook per (domain, snapshot_id)
    scenario_count     integer     NOT NULL,               -- equals count of related scenarios
    knowledge_version  text,                               -- provenance: fault-origins/templates version
    compiled_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_codebooks_domain_compiled
    ON codebook.codebooks (domain, compiled_at DESC);
CREATE INDEX IF NOT EXISTS idx_codebooks_snapshot
    ON codebook.codebooks (snapshot_id);

-- One-active-codebook contract: exactly one active row per (domain, snapshot_id)
CREATE UNIQUE INDEX IF NOT EXISTS uq_codebooks_one_active
    ON codebook.codebooks (domain, snapshot_id)
    WHERE active = true;

-- Step 2: scenarios
CREATE TABLE IF NOT EXISTS codebook.scenarios (
    scenario_id             text   PRIMARY KEY,            -- {codebook_id}:{fault_origin_object_id}
    codebook_id             text   NOT NULL
        REFERENCES codebook.codebooks (codebook_id) ON DELETE CASCADE,
    fault_origin_object_id  text   NOT NULL,               -- candidate-root-cause managedObjectId
    fault_origin_type       text   NOT NULL,               -- Fiber | LineCard | Port | Interface | Node
    predicted_symptoms      jsonb  NOT NULL,               -- ordered [{alarmType, managedObjectId}], origin first
    trail_ids               text[] NOT NULL                -- tagged trails (union across symptom objects)
);

CREATE INDEX IF NOT EXISTS idx_scenarios_codebook
    ON codebook.scenarios (codebook_id);
CREATE INDEX IF NOT EXISTS idx_scenarios_origin
    ON codebook.scenarios (codebook_id, fault_origin_object_id);

-- Step 3: processed_events (idempotency / dedup on envelope eventId)
CREATE TABLE IF NOT EXISTS codebook.processed_events (
    event_id      text        PRIMARY KEY,                 -- envelope eventId
    codebook_id   text,                                    -- codebook produced by this event (nullable)
    processed_at  timestamptz NOT NULL DEFAULT now()
);
