-- topology_meta — snapshot version metadata only (no graph data). System-of-record for the
-- current/previous snapshotId pointers and the ingest audit. Schema-scoped (CREATE SCHEMA IF NOT
-- EXISTS handled by Flyway create-schemas); touches no other schema.
CREATE SCHEMA IF NOT EXISTS topology_meta;

CREATE TABLE IF NOT EXISTS topology_meta.snapshot (
  snapshot_id          text PRIMARY KEY,
  change_type          text NOT NULL CHECK (change_type IN ('full-load','incremental')),
  domain               text NOT NULL,
  file_schema_version  int  NOT NULL,
  node_count           int  NOT NULL,
  edge_count           int  NOT NULL,
  status               text NOT NULL CHECK (status IN ('current','previous')),
  producer_supplied_id text,
  ingested_at          timestamptz NOT NULL DEFAULT now(),
  event_id             text,
  trace_id             text
);

-- At most one current and at most one previous PER DOMAIN.
CREATE UNIQUE INDEX IF NOT EXISTS uq_snapshot_status_per_domain
  ON topology_meta.snapshot(domain, status) WHERE status IN ('current','previous');

CREATE INDEX IF NOT EXISTS idx_snapshot_domain_ingested
  ON topology_meta.snapshot(domain, ingested_at DESC);
