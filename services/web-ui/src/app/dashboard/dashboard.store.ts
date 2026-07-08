import { Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { PatternManagerClient } from '../api/pattern-manager.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

@Injectable()
export class DashboardStore {
  private readonly ce = inject(CorrelationEngineClient);
  private readonly pm = inject(PatternManagerClient);
  private readonly am = inject(AlarmManagerClient);
  private readonly sim = inject(SimulatorLabelsClient);
  private readonly rcaSvc = inject(RcaAccuracyService);

  readonly stats = signal<StatsVM | null>(null);
  readonly incidents = signal<IncidentVM[]>([]);
  readonly activePatternCount = signal<number>(0);
  readonly labels = signal<GroundTruthLabel[] | null>(null);
  readonly loading = signal<boolean>(false);

  /** auto-correlation% = correlatedAlarmCount / totalAlarmsProcessed; N/A when zero processed (AC 58 – dashboard). */
  readonly autoCorrelationPct = computed<number | null>(() => {
    const s = this.stats();
    if (!s || !s.totalAlarmsProcessed || s.correlatedAlarmCount === undefined) {
      return null;
    }
    return s.correlatedAlarmCount / s.totalAlarmsProcessed;
  });

  /**
   * RCA accuracy (AC 57): eval-mode value, else the direct `rootCauseAlarmId` exact join against the
   * simulator ground-truth labels, else N/A. Both the incident and the label carry `rootCauseAlarmId`,
   * so the join needs no alarm-by-id device resolution.
   */
  readonly rcaAccuracy = computed(() =>
    this.rcaSvc.resolve(this.stats(), this.incidents(), this.labels()),
  );

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
    // Alarm Manager read drives the loading flag only; the total is no longer surfaced as a KPI.
    this.am
      .listAlarms()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe(() => this.loading.set(false));

    // Fetch the RCA ground-truth oracle (simulator `/labels`). The client resolves to `/api/simulator`
    // (same base as the synth-run trigger), so it's reachable whenever the simulator is; a failed or
    // empty fetch leaves `labels` empty and `rcaAccuracy` falls back to N/A (no fabrication).
    this.sim.listLabels().pipe(catchError(() => of([]))).subscribe((ls) => this.labels.set(ls));
  }

  // Exposed for tests/parallel-load callers that prefer a single subscription.
  loadParallel() {
    return forkJoin({
      stats: this.ce.getStats().pipe(catchError(() => of(null))),
      incidents: this.ce.listIncidents().pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 }))),
    });
  }
}
