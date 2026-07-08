import { Injectable } from '@angular/core';
import { AlarmSummary, GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

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
 * We identify each incident's declared root cause by resolving its `rootCauseAlarmId` to the actual
 * Alarm Manager alarm (`rcaAlarmsById`), which carries the exact failed DEVICE (`managedObjectId`)
 * and alarm type. The simulator label carries the ground-truth failed device
 * (`rootCauseManagedObjectId`) + its `rootCauseAlarmType`. NOTE: the frozen simulator `/labels`
 * contract does NOT expose a root-cause ALARM id, so the tightest honest per-incident key available
 * is the root-cause DEVICE (managedObjectId) — an EXACT device match, not the previous loose
 * type-membership check.
 *
 * An incident is joined to its ground-truth label by EXACT root-cause device
 * (`incident RCA alarm.managedObjectId === label.rootCauseManagedObjectId`). It counts as CORRECT
 * iff, for that same device, the incident's tagged root-cause alarm TYPE also equals the label's
 * `rootCauseAlarmType` (so a matching-device-but-wrong-type incident does NOT count).
 *
 * DENOMINATOR: only incidents that HAVE a corresponding ground-truth label are scored — i.e. those
 * whose root-cause device is a labelled root-cause device. Incidents whose device is not covered by
 * any label are excluded from BOTH numerator and denominator (the oracle simply says nothing about
 * them). accuracy = (incidents whose (device,type) exactly matches a label) / (incidents whose
 * device is covered by a label). If no incident is covered by any label, we fall back to N/A rather
 * than report 0/0.
 */
@Injectable({ providedIn: 'root' })
export class RcaAccuracyService {
  resolve(
    stats: StatsVM | null,
    incidents: readonly IncidentVM[],
    labels: readonly GroundTruthLabel[] | null,
    rcaAlarmsById: ReadonlyMap<string, AlarmSummary> | null,
  ): RcaAccuracyResult {
    if (stats && typeof stats.rcaAccuracy === 'number' && stats.rcaAccuracy !== null) {
      return { value: stats.rcaAccuracy, source: 'eval' };
    }
    if (labels && labels.length > 0 && incidents.length > 0) {
      // Ground-truth label indexed by its exact root-cause DEVICE (the per-incident join key).
      const labelByDevice = new Map<string, GroundTruthLabel>();
      for (const l of labels) {
        if (l.rootCauseManagedObjectId) {
          labelByDevice.set(l.rootCauseManagedObjectId, l);
        }
      }

      let covered = 0; // denominator: incidents whose root-cause device is a labelled device
      let correct = 0; // numerator:   ...AND whose root-cause alarm type matches that label
      for (const inc of incidents) {
        // Resolve the incident's declared root-cause alarm to its actual device + type. Prefer the
        // resolved Alarm Manager alarm (exact device); fall back to the incident-declared type only.
        const rcaAlarm = rcaAlarmsById?.get(inc.rootCauseAlarmId);
        const device = rcaAlarm?.managedObjectId;
        if (!device) {
          continue; // cannot exactly locate this incident's root-cause device → not scorable
        }
        const label = labelByDevice.get(device);
        if (!label) {
          continue; // no ground-truth label covers this device → excluded from num AND denom
        }
        covered += 1;
        const incType = rcaAlarm?.alarmType ?? rcaAlarm?.eventType ?? inc.rootCauseAlarmType;
        if (incType && incType === label.rootCauseAlarmType) {
          correct += 1;
        }
      }

      if (covered === 0) {
        return { value: null, source: 'na' }; // oracle covers none of the incidents → honest N/A
      }
      return { value: correct / covered, source: 'client-side-join' };
    }
    return { value: null, source: 'na' };
  }
}
