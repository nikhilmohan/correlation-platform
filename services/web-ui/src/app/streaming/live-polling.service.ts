import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { ApiConfigService } from '../core/api-config.service';
import { AlarmSummary, IncidentVM } from '../api/models';
import { AlarmDelta, DeltaDiffService, IncidentDelta } from './delta-diff';

/**
 * Streaming poll loop (spec tasks 2, AC 6-12). The only timer in the app. Self-rescheduling
 * setTimeout keyed off `autoRefresh` + `intervalMs` signals. First tick fires immediately, then
 * every `intervalMs`. Pause tears the loop down (no HTTP); resume restarts at the configured
 * interval. Each tick reads `.items` from the canonical page envelope of both clients and diffs
 * vs. the previous snapshot. A poll failure sets `pollError` (stale-data indicator), retains the
 * previous snapshot, and the next tick retries. A `pollInFlight` guard prevents overlap.
 */
@Injectable()
export class LivePollingService {
  private readonly am = inject(AlarmManagerClient);
  private readonly ce = inject(CorrelationEngineClient);
  private readonly config = inject(ApiConfigService);
  private readonly diff = inject(DeltaDiffService);
  private readonly destroyRef = inject(DestroyRef);

  readonly autoRefresh = signal<boolean>(true);
  readonly intervalMs = signal<number>(this.config.streamingRefreshIntervalMs);
  readonly alarmDeltas = signal<AlarmDelta[]>([]);
  readonly incidentDeltas = signal<IncidentDelta[]>([]);
  readonly lastUpdated = signal<number | null>(null);
  readonly pollError = signal<boolean>(false);

  private prevAlarms: AlarmSummary[] = [];
  private prevIncidents: IncidentVM[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;
  private pollInFlight = false;
  private started = false;

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.destroyRef.onDestroy(() => this.stop());
    this.tickThenSchedule();
  }

  stop(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  pause(): void {
    this.autoRefresh.set(false);
    this.stop();
  }

  resume(): void {
    if (this.autoRefresh()) {
      return;
    }
    this.autoRefresh.set(true);
    this.tickThenSchedule();
  }

  setInterval(ms: number): void {
    if (ms <= 0) {
      return; // reject non-positive intervals (client-side guard)
    }
    this.intervalMs.set(ms);
    if (this.autoRefresh()) {
      this.stop();
      this.tickThenSchedule();
    }
  }

  private tickThenSchedule(): void {
    this.poll();
    this.schedule();
  }

  private schedule(): void {
    this.stop();
    if (!this.autoRefresh()) {
      return;
    }
    this.timer = setTimeout(() => {
      this.poll();
      this.schedule();
    }, this.intervalMs());
  }

  private poll(): void {
    if (this.pollInFlight || !this.autoRefresh()) {
      return;
    }
    this.pollInFlight = true;
    let alarmsDone = false;
    let incidentsDone = false;
    const settle = () => {
      if (alarmsDone && incidentsDone) {
        this.pollInFlight = false;
      }
    };

    this.am
      .listAlarms()
      .pipe(catchError(() => of(null)))
      .subscribe((page) => {
        if (page) {
          const deltas = this.diff.diffAlarms(this.prevAlarms, page.items);
          this.alarmDeltas.set(deltas);
          this.prevAlarms = page.items;
          this.lastUpdated.set(Date.now());
          this.pollError.set(false);
        } else {
          this.pollError.set(true);
        }
        alarmsDone = true;
        settle();
      });

    this.ce
      .listIncidents()
      .pipe(catchError(() => of(null)))
      .subscribe((page) => {
        if (page) {
          const deltas = this.diff.diffIncidents(this.prevIncidents, page.items);
          this.incidentDeltas.set(deltas);
          this.prevIncidents = page.items;
        } else {
          this.pollError.set(true);
        }
        incidentsDone = true;
        settle();
      });
  }
}
