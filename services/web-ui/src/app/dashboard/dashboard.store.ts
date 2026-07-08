import { Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { PatternManagerClient } from '../api/pattern-manager.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { SimulatorClient } from '../api/simulator.client';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { DedupReduction, computeDedupReduction } from '../core/dedup-reduction';
import { AlarmSummary, GroundTruthLabel, IncidentVM, StatsVM, SynthSummaryModel } from '../api/models';

@Injectable()
export class DashboardStore {
  private readonly ce = inject(CorrelationEngineClient);
  private readonly pm = inject(PatternManagerClient);
  private readonly am = inject(AlarmManagerClient);
  private readonly sim = inject(SimulatorLabelsClient);
  private readonly simSvc = inject(SimulatorClient);
  private readonly rcaSvc = inject(RcaAccuracyService);

  readonly stats = signal<StatsVM | null>(null);
  readonly incidents = signal<IncidentVM[]>([]);
  readonly activePatternCount = signal<number>(0);
  readonly alarmCount = signal<number>(0);
  readonly labels = signal<GroundTruthLabel[] | null>(null);
  /** Latest simulator-run summary (carries `alarmsEmitted` = EMITTED count for the dedup card). */
  readonly synthSummary = signal<SynthSummaryModel | null>(null);
  readonly loading = signal<boolean>(false);

  /**
   * ENRICHMENT DE-DUP reduction — the SAME "Dedup reduction" KPI shown in the Alarms header, backed
   * by the SAME shared `computeDedupReduction` helper and the SAME data sources (emitted from the
   * simulator run summary, kept = Alarm Manager total). Includes the divide-by-zero + kept > emitted
   * guards so the dashboard never shows a negative / false ratio.
   */
  readonly dedupReduction = computed<DedupReduction>(() =>
    computeDedupReduction(this.synthSummary()?.alarmsEmitted ?? null, this.alarmCount()),
  );

  /** auto-correlation% = correlatedAlarmCount / totalAlarmsProcessed; N/A when zero processed (AC 58 – dashboard). */
  readonly autoCorrelationPct = computed<number | null>(() => {
    const s = this.stats();
    if (!s || !s.totalAlarmsProcessed || s.correlatedAlarmCount === undefined) {
      return null;
    }
    return s.correlatedAlarmCount / s.totalAlarmsProcessed;
  });

  /**
   * Resolved root-cause alarms (per incident), keyed by alarmId — carries each incident's exact
   * failed DEVICE (`managedObjectId`) + type for the RCA-accuracy per-incident exact join. Populated
   * after incidents load by resolving their `rootCauseAlarmId`s via the Alarm Manager.
   */
  readonly rcaAlarmsById = signal<ReadonlyMap<string, AlarmSummary>>(new Map());

  /** RCA accuracy (AC 57): eval-mode value, else per-incident exact device join, else N/A. */
  readonly rcaAccuracy = computed(() =>
    this.rcaSvc.resolve(this.stats(), this.incidents(), this.labels(), this.rcaAlarmsById()),
  );

  readonly incidentCount = computed(() => this.incidents().length);

  load(): void {
    this.loading.set(true);
    // Independent reads: a failing source degrades its own KPI, not the whole dashboard.
    this.ce.getStats().pipe(catchError(() => of(null))).subscribe((s) => this.stats.set(s));
    this.ce
      .listIncidents()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => {
        this.incidents.set(p.items);
        this.resolveRcaAlarms(p.items);
      });
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

    // Fetch the RCA ground-truth oracle (simulator `/labels`). The client resolves to `/api/simulator`
    // (same base as the synth-run trigger), so it's reachable whenever the simulator is; a failed or
    // empty fetch leaves `labels` empty and `rcaAccuracy` falls back to N/A (no fabrication).
    this.sim.listLabels().pipe(catchError(() => of([]))).subscribe((ls) => this.labels.set(ls));

    // Latest simulator-run summary for the dedup card's EMITTED count (kept = alarmCount above). A
    // missing/failed status leaves synthSummary null → the card shows kept-only / "—" (no bogus ratio).
    this.simSvc
      .getStatus()
      .pipe(catchError(() => of(null)))
      .subscribe((st) => this.synthSummary.set(st?.summary ?? null));
  }

  /**
   * Resolve each incident's root-cause alarm by id (its exact device + type) for the RCA-accuracy
   * per-incident exact join. A failed/empty fetch leaves the map empty → `rcaAccuracy` falls back to
   * N/A (no fabrication). Only root-cause ids are needed here (children don't affect RCA accuracy).
   */
  private resolveRcaAlarms(incidents: readonly IncidentVM[]): void {
    const ids = [...new Set(incidents.map((i) => i.rootCauseAlarmId).filter((id): id is string => !!id))];
    if (ids.length === 0) {
      this.rcaAlarmsById.set(new Map());
      return;
    }
    this.am
      .getAlarms(ids)
      .pipe(catchError(() => of<AlarmSummary[]>([])))
      .subscribe((alarms) => this.rcaAlarmsById.set(new Map(alarms.map((a) => [a.alarmId, a]))));
  }

  // Exposed for tests/parallel-load callers that prefer a single subscription.
  loadParallel() {
    return forkJoin({
      stats: this.ce.getStats().pipe(catchError(() => of(null))),
      incidents: this.ce.listIncidents().pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 }))),
    });
  }
}
