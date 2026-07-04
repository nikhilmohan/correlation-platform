-- [SIG-FOLD-V5] Re-key ALL remaining unexplained patterns to signatureIdentity.
--
-- V4 Part B collapsed only DUPLICATE unexplained groups (HAVING count(*) > 1). Pre-existing
-- SINGLETON unexplained rows (occurrence_count = 1, the only row for their signature at migration
-- time) were NEVER re-keyed: they kept their old perEventIdentity UUID (trailId + sourceWindowId in
-- the key). At runtime a fresh occurrence of that same signature computes the correct
-- signatureIdentity (sequence|domain|snapshotId), does NOT match the stale-id row, and INSERTs a NEW
-- row -> a duplicate. This is the V4 coverage gap (root-caused live: >= 2 dupes already appeared,
-- ~19 pre-collapse singletons at risk).
--
-- V5 generalizes V4's Part B collapse and applies it to EVERY unexplained group with the
-- `HAVING count(*) > 1` filter REMOVED:
--   * a 1-row group already at signatureIdentity  -> no-op (already correct; incl. V4-collapsed rows);
--   * a 1-row group at a stale perEventIdentity id -> re-key that one row to signatureIdentity;
--   * a multi-row group (a stale singleton + a fresh signatureIdentity row, or any stragglers)
--     -> fold/merge ALL rows into ONE row at signatureIdentity (same aggregation as the runtime fold
--        + V4 collapse).
--
-- Identity is computed with the SAME pattern.uuid_v5 function + NAMESPACE + name-string
-- (seq_csv|domain|snapshot_id, nulls COALESCEd to '') that V4 uses -- byte-identical to Java
-- UuidV5.signatureIdentity (pinned by UuidV5SqlEquivalenceIT). No new identity computation.
--
-- Idempotent + additive: safe to run twice, and a no-op on a store where V4 already collapsed the
-- dup groups (those groups now have exactly one row, already at signatureIdentity). After V5, EVERY
-- unexplained row's pattern_id == its signatureIdentity, and there is exactly ONE row per
-- (ordered sequence, domain, snapshot_id).
--
-- Reuses pattern.uuid_v5 + pattern_trail from V4 (V5 never edits V1..V4). FK is not ON UPDATE
-- CASCADE, so -- exactly as V4 -- for a group whose signature row does not yet exist we INSERT a
-- fresh survivor at new_id, re-point every child onto it, then delete the old rows. Where a
-- signatureIdentity row ALREADY exists (the live dup case), we MERGE the stale rows into that
-- existing survivor instead of duplicate-inserting.

DO $rekey$
DECLARE
    g            RECORD;
    survivor_old UUID;
    new_id       UUID;
    new_exists   BOOLEAN;
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
        -- NO `HAVING count(*) > 1`: process EVERY unexplained signature group. This is the fix.
    LOOP
        -- new_id = signatureIdentity(seq_csv, domain, snapshot_id) -- MUST match Java. Same function,
        -- NAMESPACE + 3-part name-string as V4.
        new_id := pattern.uuid_v5(
            '6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f'::uuid,
            g.seq_csv || '|' || g.dom || '|' || g.snap);

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

        -- Fast path: the ONLY member is already at signatureIdentity -> nothing to do (no-op). This
        -- covers V4-collapsed rows and already-re-keyed rows, keeping V5 idempotent.
        IF (SELECT count(*) FROM grp) = 1
           AND EXISTS (SELECT 1 FROM grp WHERE pattern_id = new_id) THEN
            DROP TABLE grp;
            CONTINUE;
        END IF;

        -- Does a signatureIdentity survivor row already exist for this group?
        new_exists := EXISTS (SELECT 1 FROM grp WHERE pattern_id = new_id);

        IF new_exists THEN
            -- MERGE case: a signatureIdentity row is present (the fresh-occurrence row) plus one or
            -- more stale-id rows. Fold the stale rows INTO the existing survivor (new_id). Aggregate
            -- exactly like the runtime fold + V4 collapse; the survivor keeps its own
            -- sample_alarm/sequence_element (fold-keeps-first) so re-point only the stale-id children.

            -- 1) Aggregate metrics of the WHOLE group onto the existing survivor row.
            UPDATE pattern.pattern surv
            SET occurrence_count = agg.occ_sum,
                instance_count   = agg.inst_sum,
                support          = agg.support_w,
                confidence       = agg.confidence_w,
                lift             = agg.lift_w,
                first_seen       = agg.first_seen,
                last_seen        = agg.last_seen,
                updated_at       = agg.last_seen
            FROM (
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
            WHERE surv.pattern_id = new_id;

            -- 2) Re-point contributing_event of the STALE members onto new_id.
            UPDATE pattern.contributing_event
            SET pattern_id = new_id
            WHERE pattern_id IN (SELECT pattern_id FROM grp WHERE pattern_id <> new_id);

            -- 3) Union pattern_trail of the stale members onto new_id, dedup on (new_id, trail_id).
            INSERT INTO pattern.pattern_trail (pattern_id, trail_id, first_seen)
            SELECT new_id, pt.trail_id, pt.first_seen
            FROM pattern.pattern_trail pt
            WHERE pt.pattern_id IN (SELECT pattern_id FROM grp WHERE pattern_id <> new_id)
            ON CONFLICT (pattern_id, trail_id) DO NOTHING;
            DELETE FROM pattern.pattern_trail
            WHERE pattern_id IN (SELECT pattern_id FROM grp WHERE pattern_id <> new_id);

            -- 4) trail_count = count(distinct trails) now on new_id (>= 1 to honour the CHECK; a row
            --    always represents at least one trail even if pattern_trail was never backfilled).
            UPDATE pattern.pattern
            SET trail_count = GREATEST(
                    (SELECT count(*) FROM pattern.pattern_trail WHERE pattern_id = new_id), 1)
            WHERE pattern_id = new_id;

            -- 5) Union supporting_instance of the stale members onto new_id, dedup on source_window_id.
            UPDATE pattern.supporting_instance
            SET pattern_id = new_id
            WHERE pattern_id IN (SELECT pattern_id FROM grp WHERE pattern_id <> new_id);
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

            -- 6) Delete the stale-id rows; their sequence_element/sample_alarm/lifecycle_transition
            --    (fold-keeps-first => survivor's kept, losers' discarded) cascade away.
            DELETE FROM pattern.pattern
            WHERE pattern_id IN (SELECT pattern_id FROM grp WHERE pattern_id <> new_id);
        ELSE
            -- RE-KEY case: no signatureIdentity row exists yet. Every member is at a stale
            -- perEventIdentity id (a lone singleton, or -- degenerate -- multiple stale rows). INSERT a
            -- fresh survivor at new_id copying the survivor's columns + aggregated metrics, re-point
            -- children, delete the old rows. Identical to V4's INSERT-fresh + re-point + delete.

            -- Survivor: earliest created_at, tie-break MIN(pattern_id).
            SELECT pattern_id INTO survivor_old
            FROM grp
            ORDER BY created_at ASC, pattern_id ASC
            LIMIT 1;

            -- 1) Insert the fresh survivor row at new_id (survivor's descriptive columns + aggregates).
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

            -- 2) Re-point contributing_event of ALL members onto new_id.
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

            -- 4) trail_count = count(distinct trails) now on new_id (>= 1 to honour the CHECK).
            UPDATE pattern.pattern
            SET trail_count = GREATEST(
                    (SELECT count(*) FROM pattern.pattern_trail WHERE pattern_id = new_id), 1)
            WHERE pattern_id = new_id;

            -- 5) Re-point supporting_instance of ALL members onto new_id, then dedup on window.
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

            -- 6) Re-point the SURVIVOR's sequence_element + sample_alarm + lifecycle_transition onto
            --    new_id (fold-keeps-first). Loser rows' copies cascade-delete in step 7.
            UPDATE pattern.sequence_element     SET pattern_id = new_id WHERE pattern_id = survivor_old;
            UPDATE pattern.sample_alarm         SET pattern_id = new_id WHERE pattern_id = survivor_old;
            UPDATE pattern.lifecycle_transition SET pattern_id = new_id WHERE pattern_id = survivor_old;

            -- 7) Delete ALL old group rows; their leftover children cascade away.
            DELETE FROM pattern.pattern
            WHERE pattern_id IN (SELECT pattern_id FROM grp);
        END IF;

        DROP TABLE grp;
    END LOOP;
END;
$rekey$;
