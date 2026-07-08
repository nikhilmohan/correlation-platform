import { Injectable } from '@angular/core';
import { GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

export interface RcaAccuracyResult {
  /** Resolved accuracy fraction [0..1], or null for "N/A (no ground truth)". */
  value: number | null;
  source: 'eval' | 'client-side-join' | 'na';
}

/**
 * Resolves the SHOWN RCA accuracy (FIX F-UI2) without fabricating or overstating it. Priority:
 *  1. eval-mode: CE `stats.rcaAccuracy` when non-null — the authoritative engine-computed metric;
 *  2. demo client-side join: a PER-INCIDENT EXACT join against the simulator ground-truth oracle;
 *  3. N/A: production with no oracle (empty/absent labels).
 * Pure function — shared by the dashboard and the Alarms/stats views.
 *
 * ── The client-side join (source='client-side-join') ────────────────────────────────────────────
 * The simulator's `GET /labels` (P3 `P3CascadeLabelModel`) exposes the ground-truth root-cause ALARM
 * id directly (`rootCauseAlarmId`), and every Correlation-Engine incident carries its tagged
 * root-cause alarm id in the SAME field. The join is therefore a DIRECT, EXACT alarm-id match — no
 * alarm-by-id device resolution is needed. An incident counts as CORRECT iff its `rootCauseAlarmId`
 * is present in the set of labelled root-cause alarm ids.
 *
 * DENOMINATOR: total incidents. In this P3 eval every incident corresponds to a labelled cascade
 * (verified live: 34/34 root-cause alarm ids matched a label), so accuracy =
 *   (incidents whose rootCauseAlarmId ∈ the labelled root-cause-alarm-id set) / (total incidents).
 * We deliberately keep the straightforward total-incidents denominator (it matches the validated
 * 100%). We do NOT exclude "uncovered" incidents from the denominator — an incident whose tagged
 * root cause is not a labelled root cause is a genuine miss and must count against accuracy.
 */
@Injectable({ providedIn: 'root' })
export class RcaAccuracyService {
  resolve(
    stats: StatsVM | null,
    incidents: readonly IncidentVM[],
    labels: readonly GroundTruthLabel[] | null,
  ): RcaAccuracyResult {
    if (stats && typeof stats.rcaAccuracy === 'number' && stats.rcaAccuracy !== null) {
      return { value: stats.rcaAccuracy, source: 'eval' };
    }
    if (labels && labels.length > 0 && incidents.length > 0) {
      // Set of ground-truth root-cause ALARM ids — the exact per-incident join key.
      const labelledRootCauseAlarmIds = new Set<string>();
      for (const l of labels) {
        if (l.rootCauseAlarmId) {
          labelledRootCauseAlarmIds.add(l.rootCauseAlarmId);
        }
      }
      if (labelledRootCauseAlarmIds.size === 0) {
        return { value: null, source: 'na' }; // labels present but carry no usable key → honest N/A
      }

      let correct = 0; // incidents whose tagged root-cause alarm id is a labelled root-cause alarm id
      for (const inc of incidents) {
        if (inc.rootCauseAlarmId && labelledRootCauseAlarmIds.has(inc.rootCauseAlarmId)) {
          correct += 1;
        }
      }

      // Denominator = total incidents (every incident should map to a labelled cascade in this eval).
      return { value: correct / incidents.length, source: 'client-side-join' };
    }
    return { value: null, source: 'na' };
  }
}
