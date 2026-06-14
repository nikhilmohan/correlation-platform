import { Injectable } from '@angular/core';
import { AlarmSummary, IncidentVM } from '../api/models';

export type DeltaKind = 'NEW' | 'CHANGED' | 'UNCHANGED' | 'GREW';

export interface AlarmDelta {
  alarmId: string;
  current: AlarmSummary;
  previousState?: string;
  currentState: string;
  kind: DeltaKind;
  highlightUntilEpochMs: number;
}

export interface IncidentDelta {
  incidentId: string;
  current: IncidentVM;
  previousChildCount?: number;
  currentChildCount: number;
  kind: DeltaKind;
  highlightUntilEpochMs: number;
}

export const HIGHLIGHT_MS = 1500;

/**
 * Pure poll-to-poll diff (design.md → DeltaDiffService / Algorithm logical flow). Receives the
 * `.items` arrays already unwrapped from the canonical `{ items, total, limit, offset }`
 * envelopes; keys by `alarmId`/`incidentId`. Alarm change is keyed on `lifecycleState` (covers
 * open→in-progress→correlated, revert back to open, →cleared). Incident GREW when
 * `childAlarmIds` length increases.
 */
@Injectable({ providedIn: 'root' })
export class DeltaDiffService {
  diffAlarms(previous: readonly AlarmSummary[], current: readonly AlarmSummary[], now = Date.now()): AlarmDelta[] {
    const prev = new Map(previous.map((a) => [a.alarmId, a]));
    return current.map((a) => {
      const before = prev.get(a.alarmId);
      let kind: DeltaKind;
      if (!before) {
        kind = 'NEW';
      } else if (before.lifecycleState !== a.lifecycleState) {
        kind = 'CHANGED';
      } else {
        kind = 'UNCHANGED';
      }
      return {
        alarmId: a.alarmId,
        current: a,
        previousState: before?.lifecycleState,
        currentState: a.lifecycleState,
        kind,
        highlightUntilEpochMs: kind === 'UNCHANGED' ? 0 : now + HIGHLIGHT_MS,
      };
    });
  }

  diffIncidents(previous: readonly IncidentVM[], current: readonly IncidentVM[], now = Date.now()): IncidentDelta[] {
    const prev = new Map(previous.map((i) => [i.incidentId, i]));
    return current.map((i) => {
      const before = prev.get(i.incidentId);
      const currentChildCount = i.childAlarmIds.length;
      let kind: DeltaKind;
      if (!before) {
        kind = 'NEW';
      } else if (before.childAlarmIds.length < currentChildCount) {
        kind = 'GREW';
      } else {
        kind = 'UNCHANGED';
      }
      return {
        incidentId: i.incidentId,
        current: i,
        previousChildCount: before?.childAlarmIds.length,
        currentChildCount,
        kind,
        highlightUntilEpochMs: kind === 'UNCHANGED' ? 0 : now + HIGHLIGHT_MS,
      };
    });
  }
}
