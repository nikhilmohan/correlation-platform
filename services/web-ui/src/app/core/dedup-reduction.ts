/**
 * Enrichment DE-DUPLICATION reduction — the single source of truth for the "Dedup reduction" KPI,
 * shared by the Alarms header (`AlarmsStore`) and the dashboard (`DashboardStore`) so both cards
 * render the SAME number with the SAME guards. Pure function — no Angular / DI.
 *
 * Inputs:
 *   - `emitted` — alarms EMITTED by the simulator run (`SynthSummaryModel.alarmsEmitted`). This is
 *     ONLY the latest run's count.
 *   - `kept`    — alarms KEPT after enrichment de-dup = the Alarm Manager `/alarms` page `total`.
 *     This spans ALL persisted alarms (potentially PRIOR runs), not just the latest run.
 *
 * Basis nuance (why the kept > emitted guard exists): `emitted` is latest-run-only while `kept` is
 * every persisted alarm. The two only reconcile onto a single-run basis right after a reset + a
 * single run. When `kept > emitted` the counts are NOT on the same basis, so a "% deduped" computed
 * from them would be NEGATIVE / misleading — we therefore clamp `deduped` to >= 0 and suppress the
 * fraction (the card then shows the kept count alone, no bogus ratio). This is acceptable for the
 * demo; the guard just prevents a misleading negative.
 */
export interface DedupReduction {
  /** Alarms EMITTED by the simulator run (`summary.alarmsEmitted`); null when no run this session. */
  emitted: number | null;
  /** Alarms KEPT after enrichment de-dup — the Alarm Manager total (`/alarms` page `total`). */
  kept: number | null;
  /** Absolute alarms removed by enrichment de-dup, clamped to >= 0; null when not resolvable. */
  deduped: number | null;
  /** Fraction of emitted alarms removed by de-dup [0..1]; null when not resolvable / not a valid basis. */
  fraction: number | null;
}

/**
 * Compute the dedup-reduction VM from the emitted + kept counts.
 *  - No run summary this session (`emitted` null) → `deduped`/`fraction` null (card shows kept-only / "—").
 *  - `emitted <= 0` → guard divide-by-zero → `fraction` null.
 *  - `kept > emitted` → counts are not on a single-run basis → clamp `deduped` to 0 and SUPPRESS the
 *    fraction (no negative %); the card then shows the kept count alone.
 */
export function computeDedupReduction(emitted: number | null, kept: number | null): DedupReduction {
  // A valid single-run basis requires a positive emitted count, a known kept count, and kept <= emitted
  // (kept > emitted means the kept total spans prior runs — not the same basis, so no honest ratio).
  const validBasis = emitted !== null && emitted > 0 && kept !== null && kept <= emitted;
  const deduped = validBasis ? Math.max(0, emitted! - kept!) : null;
  const fraction = validBasis && deduped !== null ? deduped / emitted! : null;
  return { emitted, kept, deduped, fraction };
}
