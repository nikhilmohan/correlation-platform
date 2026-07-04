-- [SIG-FOLD] Unexplained-pattern signature fold + impact metrics.
--
-- Unexplained patterns were minted per-occurrence + per-trail (perEventIdentity: trailId +
-- sourceWindowId in the key), so the SAME cascade shape across trails/windows became N duplicate
-- rows with no popularity/impact signal. The service now folds unexplained patterns cross-trail by
-- SIGNATURE (sequence, domain, snapshotId) into ONE row, accumulating occurrence + extent + impact
-- metrics.
--
-- This migration is ADDITIVE to V1/V2/V3 (never edits them). Two parts:
--   Part A: additive columns + pattern_trail table + universal backfill (safe/idempotent on any
--           store, including a clean/empty dev-test store and a fresh install);
--   Part B: ONE-TIME collapse of existing duplicate unexplained rows onto the survivor re-keyed to
--           signatureIdentity (production upgrade path), idempotent (HAVING count(*) > 1 -> no-op on
--           a second run / already-collapsed store).

-- =====================================================================================
-- Part A -- additive schema + universal backfill (idempotent)
-- =====================================================================================

ALTER TABLE pattern.pattern
    ADD COLUMN IF NOT EXISTS occurrence_count INT NOT NULL DEFAULT 1 CHECK (occurrence_count >= 1);
ALTER TABLE pattern.pattern
    ADD COLUMN IF NOT EXISTS trail_count INT NOT NULL DEFAULT 1 CHECK (trail_count >= 1);
ALTER TABLE pattern.pattern
    ADD COLUMN IF NOT EXISTS first_seen TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE pattern.pattern
    ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ NOT NULL DEFAULT now();

-- Distinct-trail set: source of truth for trail_count. Composite PK makes the runtime fold's
-- INSERT ... ON CONFLICT (pattern_id, trail_id) DO NOTHING record each trail at most once.
CREATE TABLE IF NOT EXISTS pattern.pattern_trail (
    pattern_id  UUID NOT NULL REFERENCES pattern.pattern (pattern_id) ON DELETE CASCADE,
    trail_id    TEXT NOT NULL,
    first_seen  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (pattern_id, trail_id)
);
CREATE INDEX IF NOT EXISTS idx_pattern_trail_pattern ON pattern.pattern_trail (pattern_id);

-- Backfill first_seen/last_seen from the existing timestamps (occurrence_count/trail_count remain 1
-- for a not-yet-collapsed row; the collapse in Part B recomputes them for merged groups).
UPDATE pattern.pattern SET first_seen = created_at, last_seen = updated_at;

-- Seed pattern_trail from each pattern's own representative trail_id (idempotent).
INSERT INTO pattern.pattern_trail (pattern_id, trail_id, first_seen)
SELECT pattern_id, trail_id, created_at FROM pattern.pattern
ON CONFLICT (pattern_id, trail_id) DO NOTHING;

-- =====================================================================================
-- pattern.uuid_v5(namespace, name) -- RFC 4122 v5 (SHA-1), byte-identical to Java UuidV5.from
-- =====================================================================================
-- The collapse re-keys each survivor to signatureIdentity = uuid_v5(NAMESPACE, seq_csv|domain|snap).
-- It MUST equal what the running service computes (UuidV5.signatureIdentity), or the collapsed row
-- would be invisible to the service and a NEW row would be created instead of folding. This SQL
-- function reproduces UuidV5.from exactly: SHA-1 over (16 namespace bytes || UTF-8 name), take the
-- first 16 bytes, set version=5 (byte 6) and RFC-4122 variant (byte 8). Pinned equal to Java by
-- UuidV5SqlEquivalenceIT.
-- Install pgcrypto into the `pattern` schema so digest() is resolvable from the IMMUTABLE function
-- regardless of the caller's search_path (an IMMUTABLE SQL function does not inherit it).
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA pattern;

CREATE OR REPLACE FUNCTION pattern.uuid_v5(namespace uuid, name text)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
AS $func$
    WITH h AS (
        SELECT pattern.digest(
            decode(replace(namespace::text, '-', ''), 'hex') || convert_to(name, 'UTF8'),
            'sha1'
        ) AS d
    ),
    b AS (
        SELECT
            -- first 16 bytes of the SHA-1 digest
            substring(d from 1 for 16) AS raw
        FROM h
    ),
    v AS (
        SELECT
            -- byte 7 (index 6, 1-based 7): (b & 0x0f) | 0x50  -> version 5
            set_byte(
                -- byte 9 (index 8, 1-based 9): (b & 0x3f) | 0x80 -> RFC 4122 variant
                set_byte(raw, 8, (get_byte(raw, 8) & 63) | 128),
                6, (get_byte(raw, 6) & 15) | 80
            ) AS fixed
        FROM b
    )
    SELECT encode(fixed, 'hex')::uuid FROM v;
$func$;

-- =====================================================================================
-- Part B -- one-time collapse of legacy duplicate unexplained rows (idempotent)
-- =====================================================================================
-- Groups unexplained rows (anchor_scenario_id IS NULL) by (ordered sequence, domain, snapshot_id).
-- For each group with > 1 row: pick the survivor (earliest created_at, tie MIN(pattern_id)); re-key
-- it to signatureIdentity; re-point/merge FK children onto the new id; aggregate the metrics;
-- cascade-delete the losers. NULL domain/snapshot are coalesced to '' exactly as Java nz().
DO $collapse$
DECLARE
    g            RECORD;
    survivor_old UUID;
    new_id       UUID;
BEGIN
    FOR g IN
        SELECT
            sig.seq_csv,
            sig.dom,
            sig.snap
        FROM (
            SELECT
                p.pattern_id,
                COALESCE(se.seq_csv, '')                          AS seq_csv,
                COALESCE(p.domain, '')                            AS dom,
                COALESCE(p.snapshot_id, '')                       AS snap
            FROM pattern.pattern p
            LEFT JOIN (
                SELECT pattern_id,
                       string_agg(alarm_type, ',' ORDER BY position) AS seq_csv
                FROM pattern.sequence_element
                GROUP BY pattern_id
            ) se ON se.pattern_id = p.pattern_id
            WHERE p.anchor_scenario_id IS NULL
        ) sig
        GROUP BY sig.seq_csv, sig.dom, sig.snap
        HAVING count(*) > 1
    LOOP
        -- Resolve the concrete group members for THIS (seq_csv, dom, snap).
        CREATE TEMP TABLE grp ON COMMIT DROP AS
            SELECT p.pattern_id, p.created_at, p.updated_at, p.instance_count, p.occurrence_count,
                   p.support, p.confidence, p.lift
            FROM pattern.pattern p
            LEFT JOIN (
                SELECT pattern_id,
                       string_agg(alarm_type, ',' ORDER BY position) AS seq_csv
                FROM pattern.sequence_element
                GROUP BY pattern_id
            ) se ON se.pattern_id = p.pattern_id
            WHERE p.anchor_scenario_id IS NULL
              AND COALESCE(se.seq_csv, '') = g.seq_csv
              AND COALESCE(p.domain, '')   = g.dom
              AND COALESCE(p.snapshot_id, '') = g.snap;

        -- Survivor: earliest created_at, tie-break MIN(pattern_id).
        SELECT pattern_id INTO survivor_old
        FROM grp
        ORDER BY created_at ASC, pattern_id ASC
        LIMIT 1;

        -- New id = signatureIdentity(seq_csv, domain, snapshot_id) -- MUST match Java. The
        -- perEventIdentity name-string (4 |-parts, includes trailId/sourceWindowId) differs from the
        -- signature name-string (3 |-parts), so new_id NEVER equals any group member's old id -> a
        -- fresh PK. FK is not ON UPDATE CASCADE, so we INSERT a fresh survivor row at new_id, re-point
        -- every child onto it, then delete the old group rows (their leftover children cascade away).
        new_id := pattern.uuid_v5(
            '6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f'::uuid,
            g.seq_csv || '|' || g.dom || '|' || g.snap);

        -- 1) Insert the fresh survivor row at new_id: copy the survivor's descriptive columns +
        --    aggregate the metrics over the group (weighted/member-alarm mean for the ratios,
        --    matching the runtime fold; trail_count fixed up after the trail union below).
        INSERT INTO pattern.pattern
            (pattern_id, trail_id, root_cause_alarm_type, support, confidence, lift, timing,
             codebook_match_id, reconcile_status, structurally_validated, structural_validation_reason,
             session_window_ms, session_window_type, instance_count, occurrence_count, trail_count,
             first_seen, last_seen, lifecycle, domain, anchor_scenario_id, snapshot_id,
             codebook_version, representative_weight, edit_meta, created_at, updated_at)
        SELECT
            new_id, s.trail_id, s.root_cause_alarm_type,
            agg.support_w, agg.confidence_w, agg.lift_w, s.timing,
            s.codebook_match_id, s.reconcile_status, s.structurally_validated,
            s.structural_validation_reason, s.session_window_ms, s.session_window_type,
            agg.inst_sum, agg.occ_sum, 1,
            agg.first_seen, agg.last_seen, s.lifecycle, s.domain, s.anchor_scenario_id, s.snapshot_id,
            s.codebook_version, s.representative_weight, s.edit_meta, agg.first_seen, agg.last_seen
        FROM pattern.pattern s
        CROSS JOIN (
            SELECT
                SUM(occurrence_count)                                    AS occ_sum,
                SUM(instance_count)                                      AS inst_sum,
                SUM(support * instance_count)    / SUM(instance_count)   AS support_w,
                SUM(confidence * instance_count) / SUM(instance_count)   AS confidence_w,
                SUM(lift * instance_count)       / SUM(instance_count)   AS lift_w,
                MIN(created_at)                                          AS first_seen,
                MAX(updated_at)                                          AS last_seen
            FROM grp
        ) agg
        WHERE s.pattern_id = survivor_old;

        -- 2) Re-point contributing_event of ALL members onto new_id (event_id is a globally unique PK).
        UPDATE pattern.contributing_event
        SET pattern_id = new_id
        WHERE pattern_id IN (SELECT pattern_id FROM grp);

        -- 3) Re-point pattern_trail of ALL members onto new_id, dedup on (new_id, trail_id).
        INSERT INTO pattern.pattern_trail (pattern_id, trail_id, first_seen)
        SELECT new_id, pt.trail_id, pt.first_seen
        FROM pattern.pattern_trail pt
        WHERE pt.pattern_id IN (SELECT pattern_id FROM grp)
        ON CONFLICT (pattern_id, trail_id) DO NOTHING;
        DELETE FROM pattern.pattern_trail
        WHERE pattern_id IN (SELECT pattern_id FROM grp);

        -- 4) Fix up trail_count = count(distinct trails) now on new_id.
        UPDATE pattern.pattern
        SET trail_count = (SELECT count(*) FROM pattern.pattern_trail WHERE pattern_id = new_id)
        WHERE pattern_id = new_id;

        -- 5) Re-point supporting_instance of ALL members onto new_id, then dedup on source_window_id.
        UPDATE pattern.supporting_instance
        SET pattern_id = new_id
        WHERE pattern_id IN (SELECT pattern_id FROM grp);
        DELETE FROM pattern.supporting_instance si
        USING (
            SELECT MIN(id::text) AS keep_id, source_window_id
            FROM pattern.supporting_instance
            WHERE pattern_id = new_id
            GROUP BY source_window_id
        ) k
        WHERE si.pattern_id = new_id
          AND si.source_window_id IS NOT DISTINCT FROM k.source_window_id
          AND si.id::text <> k.keep_id;

        -- 6) Re-point the SURVIVOR's sequence_element + sample_alarm + lifecycle_transition onto new_id
        --    (fold-keeps-first). Loser rows' copies cascade-delete with their pattern row in step 7.
        UPDATE pattern.sequence_element     SET pattern_id = new_id WHERE pattern_id = survivor_old;
        UPDATE pattern.sample_alarm         SET pattern_id = new_id WHERE pattern_id = survivor_old;
        UPDATE pattern.lifecycle_transition SET pattern_id = new_id WHERE pattern_id = survivor_old;

        -- 7) Delete ALL old group rows (survivor_old + losers); their leftover children cascade away.
        DELETE FROM pattern.pattern
        WHERE pattern_id IN (SELECT pattern_id FROM grp);

        DROP TABLE grp;
    END LOOP;
END;
$collapse$;
