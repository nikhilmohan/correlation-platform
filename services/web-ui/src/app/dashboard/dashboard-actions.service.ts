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
 * Single shared coordinator for the operator actions surfaced across the dashboard AND the top-nav
 * Actions menu. It is the ONE place that arbitrates mutual exclusion (no two actions run at once)
 * and it owns the flows the nav Actions menu drives.
 *
 * Two kinds of entry point share this one instance (`providedIn: 'root'`):
 *
 *  1. The DASHBOARD BUTTONS — the "Start ingestion" button and the "Reset" button — own their own
 *     rich, view-coupled flows (the reset button polls the topology back to all-green, recolours the
 *     map, guards truncated snapshots, etc.). They publish their busy state here via the writable
 *     `ingesting` / `resetting` signals so the whole app can mutually disable while they run. These
 *     two signals are the BASELINE contract and are kept unchanged.
 *
 *  2. The nav ACTIONS MENU (Mine / Ingest / Purge) — a lightweight launcher that delegates to the
 *     coordinator's own `startIngest()` / `startPurge()` / `startMine()`. These call the SAME
 *     Simulator / Alarm-Manager / Correlation-Engine client methods the buttons use:
 *       - Ingest (P3): POST /synth/run → poll /synth/status until a terminal state.
 *       - Purge  (P3): forkJoin(POST /admin/purge-live-alarms, POST /admin/reset-correlation) with a
 *         bounded safety timeout so a hung backend can't wedge the coordinator.
 *       - Mine   (P2, NEW): POST /mine/run → poll /mine/status until idle/terminal. Mining is
 *         Spark-heavy and may run long or fail on a small host — a safety timeout stops polling
 *         gracefully into a "still running" note rather than an infinite spinner. On completion it
 *         points the operator to ML → Pattern mining to review + approve the new DRAFT patterns.
 *
 * Global mutual exclusion: `busy` is true while EITHER a button-driven run (`ingesting`/`resetting`)
 * OR a coordinator-driven action (`running`) is in flight, and a coordinator-driven ingest/purge
 * also mirrors into `ingesting`/`resetting` — so the buttons and the menu can never run concurrently.
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

  // --- BASELINE contract: button-published busy state -----------------------
  // Owned by the dashboard buttons (they set these while their own flows run). Kept as writable
  // signals so the buttons keep working unchanged and mutually disable each other.

  /** True while a Simulator ingestion run is active (published by the ingestion button). */
  readonly ingesting = signal<boolean>(false);
  /** True while a live-alarm + correlation reset is in flight (published by the reset button). */
  readonly resetting = signal<boolean>(false);

  /** True when EITHER dashboard button action is busy — used to mutually disable the buttons. */
  readonly anyBusy = computed(() => this.ingesting() || this.resetting());

  // --- Coordinator-driven actions (the nav Actions menu) --------------------

  /** Which coordinator-driven action, if any, is currently running. Drives mutual exclusion. */
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

  /**
   * True while ANY action is busy — a coordinator-driven action (`running`) OR a button-driven run
   * (`ingesting`/`resetting`). The nav menu keys its item-disabled state off this, so the menu can't
   * start an action while a dashboard button is mid-flow, and vice-versa.
   */
  readonly busy = computed(() => this.running() !== null || this.ingesting() || this.resetting());
  /** The coordinator-driven action currently running (or null). */
  readonly activeAction = computed(() => this.running());

  readonly isIngesting = computed(() => this.running() === 'ingest' || this.ingesting());
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
   * Called once by the first mounting entry point. Idempotent — a second call is a no-op so we never
   * issue a duplicate /synth/status on init.
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
          this.ingesting.set(true);
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
    if (this.busy()) {
      return;
    }
    this.running.set('ingest');
    this.ingesting.set(true);
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
        this.finishIngest(
          'error',
          err.status === 422
            ? 'Could not start ingestion: the request was invalid.'
            : 'Could not start ingestion. Please try again.',
        );
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
      this.ingesting.set(false);
    }
  }

  // --- Purge ----------------------------------------------------------------

  startPurge(): void {
    if (this.busy()) {
      return;
    }
    this.running.set('purge');
    this.resetting.set(true);
    this.purgeState.set({ status: 'running', message: null });

    let settled = false;
    const safety = setTimeout(() => {
      if (!settled) {
        settled = true;
        this.finishPurge('error', 'Purge is taking longer than expected. Please retry.');
      }
    }, PURGE_SAFETY_TIMEOUT_MS);

    // Same client methods the Reset button uses; we only care about success/failure here.
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
      this.resetting.set(false);
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
        this.finishMine(
          'error',
          err.status === 422
            ? 'Could not start mining: the request was invalid.'
            : 'Could not start mining. Please try again.',
        );
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
