import { Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { PatternManagerClient } from '../api/pattern-manager.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { ApiConfigService } from '../core/api-config.service';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

@Injectable()
export class DashboardStore {
  private readonly ce = inject(CorrelationEngineClient);
  private readonly pm = inject(PatternManagerClient);
  private readonly am = inject(AlarmManagerClient);
  private readonly sim = inject(SimulatorLabelsClient);
  private readonly config = inject(ApiConfigService);
  private readonly rcaSvc = inject(RcaAccuracyService);

  readonly stats = signal<StatsVM | null>(null);
  readonly incidents = signal<IncidentVM[]>([]);
  readonly activePatternCount = signal<number>(0);
  readonly alarmCount = signal<number>(0);
  readonly labels = signal<GroundTruthLabel[] | null>(null);
  readonly loading = signal<boolean>(false);

  /** alarm-reduction ratio = totalAlarmsProcessed / totalIncidentsCreated; N/A when zero incidents (AC 1). */
  readonly alarmReductionRatio = computed<number | null>(() => {
    const s = this.stats();
    if (!s || s.totalIncidentsCreated === 0) {
      return null;
    }
    return s.totalAlarmsProcessed / s.totalIncidentsCreated;
  });

  /** auto-correlation% = correlatedAlarmCount / totalAlarmsProcessed; N/A when zero processed (AC 58). */
  readonly autoCorrelationPct = computed<number | null>(() => {
    const s = this.stats();
    if (!s || !s.totalAlarmsProcessed || s.correlatedAlarmCount === undefined) {
      return null;
    }
    return s.correlatedAlarmCount / s.totalAlarmsProcessed;
  });

  /** RCA accuracy (AC 57): eval-mode value, else client-side label join, else N/A. */
  readonly rcaAccuracy = computed(() => this.rcaSvc.resolve(this.stats(), this.incidents(), this.labels()));

  readonly incidentCount = computed(() => this.incidents().length);

  load(): void {
    this.loading.set(true);
    // Independent reads: a failing source degrades its own KPI, not the whole dashboard.
    this.ce.getStats().pipe(catchError(() => of(null))).subscribe((s) => this.stats.set(s));
    this.ce
      .listIncidents()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.incidents.set(p.items));
    this.pm
      .listPatterns({ lifecycle: 'approved' })
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.activePatternCount.set(p.total ?? p.items.length));
    this.am
      .listAlarms()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => {
        this.alarmCount.set(p.total ?? p.items.length);
        this.loading.set(false);
      });

    if (this.config.rcaLabelsEnabled && this.config.isConfigured('simulatorLabels')) {
      this.sim.listLabels().pipe(catchError(() => of([]))).subscribe((ls) => this.labels.set(ls));
    }
  }

  // Exposed for tests/parallel-load callers that prefer a single subscription.
  loadParallel() {
    return forkJoin({
      stats: this.ce.getStats().pipe(catchError(() => of(null))),
      incidents: this.ce.listIncidents().pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 }))),
    });
  }
}
