import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { ApiConfigService } from '../core/api-config.service';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import {
  AlarmSummary,
  CorrelationGroup,
  GroundTruthLabel,
  IncidentVM,
  LifecycleState,
  RunStatsRow,
  StatsVM,
} from '../api/models';

@Injectable()
export class StatsStore {
  private readonly ce = inject(CorrelationEngineClient);
  private readonly am = inject(AlarmManagerClient);
  private readonly nf = inject(NoiseFilterClient);
  private readonly sim = inject(SimulatorLabelsClient);
  private readonly config = inject(ApiConfigService);
  private readonly rcaSvc = inject(RcaAccuracyService);

  readonly incidents = signal<IncidentVM[]>([]);
  readonly stats = signal<StatsVM | null>(null);
  readonly alarms = signal<AlarmSummary[]>([]);
  readonly alarmStateFilter = signal<LifecycleState | 'all'>('all');
  readonly runStats = signal<RunStatsRow[]>([]);
  readonly runStatsTrailFilter = signal<string>('');
  readonly labels = signal<GroundTruthLabel[] | null>(null);

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

  readonly rcaAccuracy = computed(() => this.rcaSvc.resolve(this.stats(), this.incidents(), this.labels()));

  /**
   * Incidents ordered most-recent-first by `createdAt` (descending); incidents with no
   * `createdAt` sort last, then by incidentId for a stable order. The template iterates this so
   * the newest incident is always at the top of the list.
   */
  readonly sortedIncidents = computed<IncidentVM[]>(() => {
    const t = (i: IncidentVM): number => (i.createdAt ? Date.parse(i.createdAt) : Number.NEGATIVE_INFINITY);
    return [...this.incidents()].sort((a, b) => t(b) - t(a) || a.incidentId.localeCompare(b.incidentId));
  });

  readonly visibleAlarms = computed<AlarmSummary[]>(() => {
    const filter = this.alarmStateFilter();
    return filter === 'all' ? this.alarms() : this.alarms().filter((a) => a.lifecycleState === filter);
  });

  /**
   * Correlated alarms grouped by incident, root-cause first then children. Built from the
   * state-filtered `visibleAlarms()` so the lifecycle-state filter still applies within groups.
   * A group with children but no live root-cause alarm is still emitted (RCA type falls back to
   * the incident's declared `rootCauseAlarmType`) so incidents are never silently dropped. Groups
   * are ordered by most-recent activity (newest `raisedAt` in the group first).
   */
  readonly correlationGroups = computed<CorrelationGroup[]>(() => {
    const byIncident = new Map<string, AlarmSummary[]>();
    for (const a of this.visibleAlarms()) {
      if (a.role === 'none' || !a.incidentId) {
        continue;
      }
      const list = byIncident.get(a.incidentId) ?? [];
      list.push(a);
      byIncident.set(a.incidentId, list);
    }
    const incidentType = new Map(this.incidents().map((i) => [i.incidentId, i.rootCauseAlarmType]));
    const groups: CorrelationGroup[] = [];
    const raisedMs = (a: AlarmSummary): number => (a.raisedAt ? Date.parse(a.raisedAt) : 0);
    for (const [incidentId, members] of byIncident) {
      const rootCause = members.find((a) => a.role === 'root-cause') ?? null;
      // Children ordered by raisedAt ASCENDING so the cascade reads in time order under the RCA.
      const children = members
        .filter((a) => a.role !== 'root-cause')
        .sort((a, b) => raisedMs(a) - raisedMs(b) || a.alarmId.localeCompare(b.alarmId));
      // Group header timestamp: prefer the RCA alarm's raisedAt, else the newest member's.
      const newest = members.reduce<string | undefined>(
        (acc, a) => (a.raisedAt && (!acc || Date.parse(a.raisedAt) > Date.parse(acc)) ? a.raisedAt : acc),
        undefined,
      );
      groups.push({
        incidentId,
        rootCause,
        rootCauseAlarmType: rootCause?.alarmType ?? rootCause?.eventType ?? incidentType.get(incidentId) ?? null,
        children,
        groupRaisedAt: rootCause?.raisedAt ?? newest,
      });
    }
    const latest = (g: CorrelationGroup): number => {
      const all = g.rootCause ? [g.rootCause, ...g.children] : g.children;
      return all.reduce((max, a) => Math.max(max, a.raisedAt ? Date.parse(a.raisedAt) : 0), 0);
    };
    return groups.sort((a, b) => latest(b) - latest(a) || a.incidentId.localeCompare(b.incidentId));
  });

  /**
   * Uncorrelated alarms (role='none' / no incident) — rendered as a flat list, not a group.
   * Ordered most-recent-first by `raisedAt` (descending); alarms with no `raisedAt` sort last.
   */
  readonly uncorrelatedAlarms = computed<AlarmSummary[]>(() => {
    const t = (a: AlarmSummary): number => (a.raisedAt ? Date.parse(a.raisedAt) : Number.NEGATIVE_INFINITY);
    return this.visibleAlarms()
      .filter((a) => a.role === 'none' || !a.incidentId)
      .sort((a, b) => t(b) - t(a) || a.alarmId.localeCompare(b.alarmId));
  });

  /** storm-reduction ratio = alarmsIn / clustersFormed (guarded). */
  stormReduction(row: RunStatsRow): number | null {
    if (row.stormReductionRatio !== undefined) {
      return row.stormReductionRatio;
    }
    return row.clustersFormed > 0 ? row.alarmsIn / row.clustersFormed : null;
  }

  loadStats(): void {
    this.ce.getStats().pipe(catchError(() => of(null))).subscribe((s) => this.stats.set(s));
    if (this.config.rcaLabelsEnabled && this.config.isConfigured('simulatorLabels')) {
      this.sim.listLabels().pipe(catchError(() => of([]))).subscribe((ls) => this.labels.set(ls));
    }
  }

  loadIncidents(): void {
    this.ce
      .listIncidents()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.incidents.set(p.items));
  }

  loadAlarms(): void {
    this.am
      .listAlarms()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.alarms.set(p.items));
  }

  loadRunStats(trailId?: string): void {
    this.runStatsTrailFilter.set(trailId ?? '');
    this.nf
      .listRunStats({ trailId: trailId || undefined })
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.runStats.set(p.items));
  }

  setAlarmFilter(state: LifecycleState | 'all'): void {
    this.alarmStateFilter.set(state);
  }
}
