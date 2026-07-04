-- V3 — pattern generalization (spec Task NEW / AC44). Adds the discovery-trail provenance to the
-- Incident Store. Under generalization an approved pattern auto-correlates on ANY structurally
-- compatible trail; `trail_id` is the trail the cascade actually occurred on (matchedTrailId), while
-- `discovery_trail_id` records the trail the pattern was originally mined on (provenance).
--
-- Additive + nullable so existing rows and the frozen event contract are unaffected. Read-model /
-- audit only: served on GET /incidents{,/{id}}, NEVER on CorrelationResultEvent.
-- NULL for codebook-decode incidents (no pattern discovery trail).

ALTER TABLE incident.incident ADD COLUMN IF NOT EXISTS discovery_trail_id text NULL;
