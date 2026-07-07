import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, forkJoin, of } from 'rxjs';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { AlarmSummary, IncidentVM, LifecycleState, StatsVM } from '../api/models';

/** How many incidents to pull so ALL live incidents are covered (they are older than the flat tail). */
const INCIDENT_PAGE_LIMIT = 200;
/** How many recent (raw, uncorrelated) alarms to pull for the plain "open" tail. */
const OPEN_TAIL_LIMIT = 50;

/**
 * One row in the unified Alarms stream (Part 3). The stream is a FLAT, timestamp-descending list of
 * `AlarmRow`s where each row is one of:
 *   - `rca`   — a root-cause alarm (role='root-cause'). Highlighted, carries a clickable incident
 *               link, and owns its correlated `children` (rendered nested/expandable beneath it).
 *   - `plain` — an uncorrelated alarm (role='none' / no incident). `children` is always empty.
 * Child alarms are NOT emitted as their own top-level rows; they live under their RCA row (grouped),
 * so the top-level stream interleaves RCA rows + uncorrelated rows by their own timestamp.
 */
export interface AlarmRow {
  kind: 'rca' | 'plain';
  alarm: AlarmSummary;
  incidentId: string | null;
  /** Correlated child alarms (role='child', same incidentId), ordered by their own raisedAt asc. */
  children: AlarmSummary[];
}

/**
 * Store backing the unified Alarms view (Part 3 — merges the old Streaming table + the Stats
 * Incidents/Alarm-lifecycle tabs). Loads alarms (Alarm Manager `/alarms`), incidents + stats
 * (Correlation Engine) and derives:
 *   - the KPI header numbers (auto-correlation %, alarm-reduction ratio, RCA accuracy, live incident
 *     count, alarms processed) — same formulas as the dashboard/stats so the numbers match, and
 *   - `rows()`: the timestamp-desc stream of RCA rows (with nested children) + uncorrelated rows,
 *     honouring the optional lifecycle-state filter.
 */
@Injectable()
export class AlarmsStore {
  private readonly am = inject(AlarmManagerClient);
  private readonly ce = inject(CorrelationEngineClient);
  private readonly rcaSvc = inject(RcaAccuracyService);

  readonly alarms = signal<AlarmSummary[]>([]);
  readonly incidents = signal<IncidentVM[]>([]);
  readonly stats = signal<StatsVM | null>(null);
  readonly stateFilter = signal<LifecycleState | 'all'>('all');

  // ── KPI header numbers (mirror the dashboard/stats formulas) ────────────────────────────────
  readonly alarmReductionRatio = computed<number | null>(() => {
    const s = this.stats();
    return s && s.totalIncidentsCreated > 0 ? s.totalAlarmsProcessed / s.totalIncidentsCreated : null;
  });

  readonly autoCorrelationPct = computed<number | null>(() => {
    const s = this.stats();
    if (!s || !s.totalAlarmsProcessed || s.correlatedAlarmCount === undefined) {
      return null;
    }
    return s.correlatedAlarmCount / s.totalAlarmsProcessed;
  });

  readonly rcaAccuracy = computed(() => this.rcaSvc.resolve(this.stats(), this.incidents(), null));

  readonly liveIncidentCount = computed<number>(() => this.incidents().length);
  readonly alarmsProcessed = computed<number>(() => this.stats()?.totalAlarmsProcessed ?? 0);

  /** Alarms after the (optional) lifecycle-state filter. */
  private readonly filteredAlarms = computed<AlarmSummary[]>(() => {
    const f = this.stateFilter();
    return f === 'all' ? this.alarms() : this.alarms().filter((a) => a.lifecycleState === f);
  });

  /**
   * The unified stream (Part 3). Built from the filtered alarms:
   *   1. Group correlated alarms (role != 'none' && incidentId) by incident → an RCA row + children.
   *      The RCA row's timestamp is the root-cause alarm's raisedAt (else the group's newest member).
   *   2. Uncorrelated alarms become `plain` rows.
   *   3. Sort the RESULTING top-level rows by timestamp DESCENDING (most recent first). Children stay
   *      grouped under their RCA row, ordered by their own raisedAt ascending (cascade order).
   * When the lifecycle filter drops the root-cause alarm but keeps children, the newest surviving
   * member is promoted to the row's `alarm` so the incident is never silently lost.
   */
  readonly rows = computed<AlarmRow[]>(() => {
    const alarms = this.filteredAlarms();
    const ms = (a: AlarmSummary): number => (a.raisedAt ? Date.parse(a.raisedAt) : 0);

    const byIncident = new Map<string, AlarmSummary[]>();
    const plain: AlarmSummary[] = [];
    for (const a of alarms) {
      if (a.role === 'none' || !a.incidentId) {
        plain.push(a);
      } else {
        const list = byIncident.get(a.incidentId) ?? [];
        list.push(a);
        byIncident.set(a.incidentId, list);
      }
    }

    const rows: AlarmRow[] = [];
    for (const [incidentId, members] of byIncident) {
      const children = members
        .filter((a) => a.role !== 'root-cause')
        .sort((a, b) => ms(a) - ms(b) || a.alarmId.localeCompare(b.alarmId));
      const rootCause =
        members.find((a) => a.role === 'root-cause') ??
        // No surviving root-cause row (e.g. filtered out): promote the newest member so the incident
        // still surfaces with a clickable incident link.
        members.reduce<AlarmSummary | null>((acc, a) => (!acc || ms(a) > ms(acc) ? a : acc), null);
      if (!rootCause) {
        continue;
      }
      const rest = children.filter((c) => c.alarmId !== rootCause.alarmId);
      rows.push({ kind: 'rca', alarm: rootCause, incidentId, children: rest });
    }
    for (const a of plain) {
      rows.push({ kind: 'plain', alarm: a, incidentId: null, children: [] });
    }

    return rows.sort((a, b) => ms(b.alarm) - ms(a.alarm) || a.alarm.alarmId.localeCompare(b.alarm.alarmId));
  });

  /**
   * INCIDENT-FIRST load (the fix). The flat `/alarms` window only ever returns the freshest,
   * still-UNCORRELATED tail — correlated alarms are older and never appear there — so grouping off a
   * flat page yields zero groups. Instead we drive the view from the Correlation Engine's incidents:
   *
   *   1. `GET /incidents?limit=200`   — every live incident (RCA id + child ids + trail + confidence).
   *   2. `GET /stats`                 — the KPI header numbers.
   *   3. `GET /alarms?limit=50`       — the recent raw/open tail (the legitimate uncorrelated rows).
   *   4. For every incident, resolve its RCA + child alarms by id via `AlarmManagerClient.getAlarms`
   *      (a concurrent `GET /alarms/{id}` fan-out; a 404 on one id skips just that alarm). All
   *      incidents' id sets are resolved together in one `forkJoin`.
   *
   * The resolved correlated alarms + the de-duped open tail are merged into `alarms()`; `rows()` then
   * groups them exactly as before (RCA row + nested children, interleaved with plain rows by
   * timestamp-desc). Nothing here changes any service contract.
   */
  loadAll(): void {
    forkJoin({
      incidents: this.ce.listIncidents({ limit: INCIDENT_PAGE_LIMIT }).pipe(catchError(() => of(null))),
      stats: this.ce.getStats().pipe(catchError(() => of(null))),
      openTail: this.am.listAlarms({ limit: OPEN_TAIL_LIMIT }).pipe(catchError(() => of(null))),
    }).subscribe(({ incidents, stats, openTail }) => {
      if (stats) {
        this.stats.set(stats);
      }
      const incidentList = incidents?.items ?? [];
      this.incidents.set(incidentList);
      this.resolveAndAssemble(incidentList, openTail?.items ?? []);
    });
  }

  setStateFilter(state: LifecycleState | 'all'): void {
    this.stateFilter.set(state);
  }

  /**
   * Resolve every incident's alarms by id (RCA + children), then merge the correlated result with the
   * recent open tail into `alarms()`. An incident referencing an id that 404s still renders (the
   * missing member is skipped by `getAlarms`). When there are no incidents we still show the open
   * tail. Resilient to the by-id fan-out failing wholesale (falls back to the open tail alone).
   */
  private resolveAndAssemble(incidents: readonly IncidentVM[], openTail: readonly AlarmSummary[]): void {
    const ids = new Set<string>();
    for (const inc of incidents) {
      if (inc.rootCauseAlarmId) {
        ids.add(inc.rootCauseAlarmId);
      }
      for (const cid of inc.childAlarmIds ?? []) {
        ids.add(cid);
      }
    }
    if (ids.size === 0) {
      this.alarms.set(this.mergeOpenTail([], openTail));
      return;
    }
    this.am
      .getAlarms([...ids])
      .pipe(catchError(() => of<AlarmSummary[]>([])))
      .subscribe((correlated) => {
        this.alarms.set(this.mergeOpenTail(correlated, openTail));
      });
  }

  /**
   * Merge the incident-resolved correlated alarms with the recent open tail, DE-DUPING: any tail
   * alarm whose id already appears inside a resolved group is dropped (it must render only once, in
   * its group). Correlated alarms win on id collision.
   */
  private mergeOpenTail(correlated: readonly AlarmSummary[], openTail: readonly AlarmSummary[]): AlarmSummary[] {
    const byId = new Map<string, AlarmSummary>();
    for (const a of correlated) {
      byId.set(a.alarmId, a);
    }
    for (const a of openTail) {
      if (!byId.has(a.alarmId)) {
        byId.set(a.alarmId, a);
      }
    }
    return [...byId.values()];
  }

  /**
   * Apply a live poll snapshot (from `LivePollingService`) to the store's incident + alarm signals so
   * `rows()`, the KPI numbers and the incident grouping all update in real time. The snapshot carries
   * the fresh incident list + the raw open tail; we re-resolve the incidents' alarms by id (moving a
   * newly-correlated alarm out of the plain tail and into its incident group live). A null snapshot on
   * an errored tick leaves the previous data intact — the caller keeps the last-good view and shows a
   * stale indicator instead of blanking the table.
   */
  applyLiveSnapshot(alarms: AlarmSummary[] | null, incidents: IncidentVM[] | null): void {
    if (incidents) {
      this.incidents.set(incidents);
    }
    // Re-resolve against the freshest incident list we have, using the freshest open tail we have.
    const inc = incidents ?? this.incidents();
    const tail = alarms ?? [];
    if (incidents || alarms) {
      this.resolveAndAssemble(inc, tail);
    }
  }

  /** Refresh the Correlation Engine stats (the KPI header) — the poll loop does not carry them. */
  refreshStats(): void {
    this.ce
      .getStats()
      .pipe(catchError(() => of(null)))
      .subscribe((s) => {
        if (s) {
          this.stats.set(s);
        }
      });
  }
}
