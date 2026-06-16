import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { ApiConfigService } from '../core/api-config.service';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { AlarmSummary, GroundTruthLabel, IncidentVM, LifecycleState, RunStatsRow, StatsVM } from '../api/models';

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

  readonly visibleAlarms = computed<AlarmSummary[]>(() => {
    const filter = this.alarmStateFilter();
    return filter === 'all' ? this.alarms() : this.alarms().filter((a) => a.lifecycleState === filter);
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
