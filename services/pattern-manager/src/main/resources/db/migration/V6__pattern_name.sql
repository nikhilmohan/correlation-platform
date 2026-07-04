-- [PATTERN-NAME] Persist a deterministic, readable pattern_name owned by the Pattern Manager.
--
-- The Pattern Manager is the SINGLE OWNER of pattern identity, so the readable name is COMPUTED,
-- PERSISTED and SERVED here (never derived client-side by consumers, which would drift). At runtime
-- the name is set at head-create time by the Java helper PatternNaming.patternName(...). This
-- migration (a) adds the column and (b) backfills EVERY pre-existing row with the SAME derivation
-- so the persisted value is byte-identical to the Java output for the same inputs.
--
-- Name format (mirror PatternNaming exactly):
--   "<label> Cascade · <short8>"
--   * <label>   : root_cause_alarm_type mapped via the CASE below (same map as the Java class +
--                 web-ui ALARM_TYPE_LABELS); an unknown token falls back to the raw token; a
--                 null/blank token degrades to 'Unknown'.
--   * SEPARATOR : ' · ' (a middot, U+00B7 — the SAME character emitted by the Java helper).
--   * <short8>  : lower(left(replace(pattern_id::text,'-',''), 8)) — first 8 hex chars, dashes
--                 stripped, lower-cased.
--
-- Degradation note: the Java helper omits the suffix when the id yields < 8 hex chars. All stored
-- pattern_id values are full UUIDs (16 bytes -> 32 hex), so the suffix is ALWAYS present in the
-- backfill; we therefore always append it here (the <8-hex degradation path is unreachable for real
-- rows). This keeps the SQL simple while remaining byte-identical to Java for every real input.
--
-- Additive + idempotent-friendly (ADD COLUMN IF NOT EXISTS); never edits V1..V5.

ALTER TABLE pattern.pattern
    ADD COLUMN IF NOT EXISTS pattern_name TEXT;

-- Backfill: same label map + ' · ' middot + short8-of-pattern_id as PatternNaming.
UPDATE pattern.pattern
SET pattern_name =
    (CASE root_cause_alarm_type
        WHEN 'AdjDown'            THEN 'Adjacency Down'
        WHEN 'BGPPeerDown'       THEN 'BGP Peer Down'
        WHEN 'ISISAdjacencyDown' THEN 'IS-IS Adjacency Down'
        WHEN 'OSPFAdjacencyDown' THEN 'OSPF Adjacency Down'
        WHEN 'RouteFlap'         THEN 'Route Flap'
        WHEN 'LDPSessionDown'    THEN 'LDP Session Down'
        WHEN 'LSPDown'           THEN 'LSP Down'
        WHEN 'FRRSwitchover'     THEN 'FRR Switchover'
        WHEN 'TETunnelDown'      THEN 'TE Tunnel Down'
        WHEN 'LinkDown'          THEN 'Link Down'
        WHEN 'IPLinkDown'        THEN 'IP Link Down'
        WHEN 'FiberFault'        THEN 'Fiber Fault'
        WHEN 'LOS'               THEN 'Loss of Signal'
        WHEN 'LOF'               THEN 'Loss of Frame'
        WHEN 'InterfaceDown'     THEN 'Interface Down'
        WHEN 'PortDown'          THEN 'Port Down'
        WHEN 'PortFlap'          THEN 'Port Flap'
        -- Unknown token -> raw token; null/blank -> 'Unknown' (mirrors alarmTypeLabel).
        ELSE COALESCE(NULLIF(TRIM(root_cause_alarm_type), ''), 'Unknown')
     END)
    || ' Cascade'
    || ' ' || chr(183) || ' '                                  -- ' · ' (U+00B7 middot)
    || lower(left(replace(pattern_id::text, '-', ''), 8))      -- short8 hex
WHERE pattern_name IS NULL;

-- NOT NULL is intentionally NOT enforced at the DB level. The runtime create path
-- (PatternStoreService.createDraftRow) always populates pattern_name and this migration backfills
-- every existing row, so real rows are never null. But the V5 rekey/merge migration's fresh-survivor
-- INSERT copies a fixed column list that (correctly) does not name a column added later in V6; a live
-- NOT NULL would break that older migration if it were ever re-executed against a V6 schema (the V5
-- ITs do exactly that). The PatternViewMapper defensively re-derives the name if the column is ever
-- null, so the read model can never serve a null name regardless.
