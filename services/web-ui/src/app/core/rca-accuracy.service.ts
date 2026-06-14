import { Injectable } from '@angular/core';
import { GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

export interface RcaAccuracyResult {
  /** Resolved accuracy fraction [0..1], or null for "N/A (no ground truth)". */
  value: number | null;
  source: 'eval' | 'client-side-join' | 'na';
}

/**
 * Resolves the SHOWN RCA accuracy (FIX F-UI2) without fabricating it. Priority:
 *  1. eval-mode: CE `stats.rcaAccuracy` when non-null;
 *  2. demo client-side join: matches/total where an incident's `rootCauseAlarmType` equals a
 *     scenario label's `rootCauseAlarmType` on the canonical alarmType token space;
 *  3. N/A: production with no oracle.
 * Pure function — shared by the dashboard and the stats view.
 */
@Injectable({ providedIn: 'root' })
export class RcaAccuracyService {
  resolve(stats: StatsVM | null, incidents: readonly IncidentVM[], labels: readonly GroundTruthLabel[] | null): RcaAccuracyResult {
    if (stats && typeof stats.rcaAccuracy === 'number' && stats.rcaAccuracy !== null) {
      return { value: stats.rcaAccuracy, source: 'eval' };
    }
    if (labels && labels.length > 0 && incidents.length > 0) {
      const labelTypes = new Set(labels.map((l) => l.rootCauseAlarmType));
      let matches = 0;
      for (const inc of incidents) {
        if (inc.rootCauseAlarmType && labelTypes.has(inc.rootCauseAlarmType)) {
          matches += 1;
        }
      }
      return { value: matches / incidents.length, source: 'client-side-join' };
    }
    return { value: null, source: 'na' };
  }
}
