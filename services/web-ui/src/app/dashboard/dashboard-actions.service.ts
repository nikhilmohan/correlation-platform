import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { SimulatorClient } from '../api/simulator.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { ApiConfigService } from '../core/api-config.service';
import {
  MineProgress,
  MineSummaryModel,
  SynthProgress,
  SynthRunStatus,
  SynthSummaryModel,
} from '../api/models';

/**
 * Shared coordinator for the three operator actions surfaced both on the dashboard buttons and in
 * the top-nav Actions menu. It owns the ONE implementation of each flow (ingest / purge / mine)
 * plus a mutual-exclusion busy signal, so no two actions run at once and every entry point stays
 * in sync.
 *
 *   - Ingest (P3): POST /synth/run → poll /synth/status until a terminal state (Alarms tab updates
 *     itself; this only drives the trigger + a status poll).
 *   - Purge  (P3): forkJoin(POST /admin/purge-live-alarms, POST /admin/reset-correlation) with a
 *     bounded safety timeout so a hung backend can't wedge the coordinator.
 *   - Mine   (P2): POST /mine/run → poll /mine/status until idle/terminal. Mining is Spark-heavy and
 *     may run long or fail on a small host — a safety timeout stops polling gracefully into a
 *     "still running" note rather than an infinite spinner. On completion it points the operator to
 *     ML → Pattern mining to review + approve the new DRAFT patterns (never auto-approved).
 *
 * State is exposed as signals; components read them for spinners/labels/results. `providedIn:root`
 * so the dashboard button and the nav menu share a single instance.
 */

export type ActionKind = 'ingest' | 'purge' | 'mine';

/** Idle | running | error | done for a single action. 'error' surfaces an inline message. */
interface ActionState {
  status: 'idle' | 'running' | 'error' | 'done';
  message: string | null;
}

const IDLE: ActionState = { status: 'idle', message: null };

/** Mine may not complete on a small host; stop polling after this window with a "still running" note. */
const MINE_SAFETY_TIMEOUT_MS = 10 * 60 * 1000;
/** Purge is a couple of quick admin POSTs; bound the wait so a hung backend can't wedge the busy flag. */
const PURGE_SAFETY_TIMEOUT_MS = 30 * 1000;

@Injectable({ providedIn: 'root' })
export class DashboardActionsService {
  private readonly sim = inject(SimulatorClient);
  private readonly alarmManager = inject(AlarmManagerClient);
  private readonly correlation = inject(CorrelationEngineClient);
  private readonly config = inject(ApiConfigService);
  private readonly destroyRef = inject(DestroyRef);

  /** Which action, if any, is currently running. Drives mutual exclusion. */
  private readonly running = signal<ActionKind | null>(null);

  private readonly ingestState = signal<ActionState>({ ...IDLE });
  private readonly purgeState = signal<ActionState>({ ...IDLE });
  private readonly mineState = signal<ActionState>({ ...IDLE });

  // Live progress for the two long-running / polled actions.
  private readonly ingestProgressSig = signal<SynthProgress | null>(null);
  private readonly mineProgressSig = signal<MineProgress | null>(null);
  private readonly mineSummarySig = signal<MineSummaryModel | null>(null);
  private readonly ingestSummarySig = signal<SynthSummaryModel | null>(null);

  // --- Public read-only view ------------------------------------------------

  /** True while ANY action is running (mutual exclusion — components disable the others). */
  readonly busy = computed(() => this.running() !== null);
  /** The action currently running (or null). */
  readonly activeAction = computed(() => this.running());

  readonly isIngesting = computed(() => this.running() === 'ingest');
  readonly isPurging = computed(() => this.running() === 'purge');
  readonly isMining = computed(() => this.running() === 'mine');

  readonly ingest = this.ingestState.asReadonly();
  readonly purge = this.purgeState.asReadonly();
  readonly mine = this.mineState.asReadonly();

  readonly ingestProgress = this.ingestProgressSig.asReadonly();
  readonly mineProgress = this.mineProgressSig.asReadonly();
  readonly mineSummary = this.mineSummarySig.asReadonly();

  /** Poll cadence: reuse the app streaming interval, clamped to the ~1.5–2s window. */
  private readonly pollMs = Math.min(2000, Math.max(1500, this.config.streamingRefreshIntervalMs));

  private ingestTimer: ReturnType<typeof setTimeout> | null = null;
  private mineTimer: ReturnType<typeof setTimeout> | null = null;
  private mineDeadline = 0;

  private initialised = false;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.stopIngestPolling();
      this.stopMinePolling();
    });
  }

  /**
   * Reflect an ingest run already started elsewhere (the CLI, another tab) into the shared state.
   * Called once by the first mounting entry point (the dashboard button / nav). Idempotent — a
   * second call is a no-op so we never issue a duplicate /synth/status on init.
   */
  initFromServer(): void {
    if (this.initialised) {
      return;
    }
    this.initialised = true;
    this.sim.getStatus().subscribe({
      next: (s) => {
        this.applyIngestStatus(s.status, s.progress, s.summary);
        if (s.status === 'running') {
          this.running.set('ingest');
          this.ingestState.set({ status: 'running', message: null });
          this.scheduleIngestPoll();
        }
      },
      error: () => {
        /* graceful degrade — stays idle */
      },
    });
  }

  // --- Ingest ---------------------------------------------------------------

  startIngest(): void {
    if (this.busy() && !this.isIngesting()) {
      return;
    }
    if (this.isIngesting()) {
      return;
    }
    this.running.set('ingest');
    this.ingestState.set({ status: 'running', message: null });
    this.ingestSummarySig.set(null);
    this.ingestProgressSig.set(null);
    this.sim.startRun({}).subscribe({
      next: () => this.scheduleIngestPoll(),
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // A run is already active — not a failure: switch straight into polling.
          this.scheduleIngestPoll();
          return;
        }
        this.finishIngest('error', err.status === 422
          ? 'Could not start ingestion: the request was invalid.'
          : 'Could not start ingestion. Please try again.');
      },
    });
  }

  private scheduleIngestPoll(): void {
    this.stopIngestPolling();
    this.pollIngestOnce();
    this.ingestTimer = setTimeout(() => this.scheduleIngestPoll(), this.pollMs);
  }

  private stopIngestPolling(): void {
    if (this.ingestTimer !== null) {
      clearTimeout(this.ingestTimer);
      this.ingestTimer = null;
    }
  }

  private pollIngestOnce(): void {
    this.sim.getStatus().subscribe({
      next: (s) => this.applyIngestStatus(s.status, s.progress, s.summary),
      error: () => {
        /* keep polling on a transient error */
      },
    });
  }

  private applyIngestStatus(
    status: SynthRunStatus,
    progress: SynthProgress,
    summary: SynthSummaryModel | null,
  ): void {
    this.ingestProgressSig.set(progress);
    this.ingestSummarySig.set(summary);
    if (status === 'completed') {
      const line = summary ? `${summary.alarmsEmitted} alarms emitted` : 'Ingestion complete';
      this.finishIngest('done', line);
    } else if (status === 'failed') {
      this.finishIngest('error', summary?.failureReason ?? 'Ingestion failed.');
    }
  }

  private finishIngest(status: 'done' | 'error', message: string): void {
    this.stopIngestPolling();
    this.ingestState.set({ status, message });
    if (this.running() === 'ingest') {
      this.running.set(null);
    }
  }

  // --- Purge ----------------------------------------------------------------

  startPurge(): void {
    if (this.busy()) {
      return;
    }
    this.running.set('purge');
    this.purgeState.set({ status: 'running', message: null });

    let settled = false;
    const safety = setTimeout(() => {
      if (!settled) {
        settled = true;
        this.finishPurge('error', 'Purge is taking longer than expected. Please retry.');
      }
    }, PURGE_SAFETY_TIMEOUT_MS);

    forkJoin([this.alarmManager.purgeLiveAlarms(), this.correlation.resetCorrelation()]).subscribe({
      next: () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(safety);
        this.finishPurge('done', 'Live alarms and correlation state cleared.');
      },
      error: () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(safety);
        this.finishPurge('error', 'Could not purge. Please try again.');
      },
    });
  }

  private finishPurge(status: 'done' | 'error', message: string): void {
    this.purgeState.set({ status, message });
    if (this.running() === 'purge') {
      this.running.set(null);
    }
  }

  // --- Mine -----------------------------------------------------------------

  startMine(): void {
    if (this.busy()) {
      return;
    }
    this.running.set('mine');
    this.mineState.set({ status: 'running', message: null });
    this.mineSummarySig.set(null);
    this.mineProgressSig.set(null);
    this.mineDeadline = Date.now() + MINE_SAFETY_TIMEOUT_MS;
    this.sim.startMine({}).subscribe({
      next: () => this.scheduleMinePoll(),
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // A mine run is already active — enter polling rather than erroring.
          this.scheduleMinePoll();
          return;
        }
        this.finishMine('error', err.status === 422
          ? 'Could not start mining: the request was invalid.'
          : 'Could not start mining. Please try again.');
      },
    });
  }

  private scheduleMinePoll(): void {
    this.stopMinePolling();
    if (Date.now() >= this.mineDeadline) {
      // Safety timeout: mining is resource-intensive and may not complete on a small host. Stop
      // polling and leave a clear "still running on the server" note (not a hard failure).
      this.mineState.set({
        status: 'done',
        message:
          'Mining is still running on the server (this can take a while). ' +
          'Check ML → Pattern mining shortly for new draft patterns.',
      });
      if (this.running() === 'mine') {
        this.running.set(null);
      }
      return;
    }
    this.pollMineOnce();
    this.mineTimer = setTimeout(() => this.scheduleMinePoll(), this.pollMs);
  }

  private stopMinePolling(): void {
    if (this.mineTimer !== null) {
      clearTimeout(this.mineTimer);
      this.mineTimer = null;
    }
  }

  private pollMineOnce(): void {
    this.sim.getMineStatus().subscribe({
      next: (s) => {
        this.mineProgressSig.set(s.progress);
        this.mineSummarySig.set(s.summary);
        if (s.status === 'idle') {
          const terminal = s.summary?.status ?? 'completed';
          if (terminal === 'failed') {
            this.finishMine('error', s.summary?.failureReason ?? 'Mining failed.');
          } else {
            const n = s.summary?.alarmsEmitted ?? s.progress.alarmsEmitted;
            this.finishMine(
              'done',
              `Mining complete — ${n} alarms generated. ` +
                'Review & approve new patterns in ML → Pattern mining.',
            );
          }
        }
      },
      error: () => {
        /* keep polling on a transient error */
      },
    });
  }

  private finishMine(status: 'done' | 'error', message: string): void {
    this.stopMinePolling();
    this.mineState.set({ status, message });
    if (this.running() === 'mine') {
      this.running.set(null);
    }
  }
}
