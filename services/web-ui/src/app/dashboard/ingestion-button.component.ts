import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { SimulatorClient } from '../api/simulator.client';
import { ApiConfigService } from '../core/api-config.service';
import { SynthProgress, SynthRunStatus, SynthSummaryModel } from '../api/models';

/**
 * "Start ingestion" button (dashboard). Kicks off the Simulator's synthetic alarm run and spins
 * while it ingests, then returns to idle when the run completes/fails. The Alarms tab updates in
 * real time on its own (LivePollingService) — this component only drives the trigger + a small
 * self-rescheduling status poll; it does NOT touch the alarm stream.
 *
 * State machine (from GET /synth/status.status + the POST outcome):
 *   idle → (click POST /synth/run) → running (spinner, disabled, poll every ~pollMs)
 *   running → completed → idle (+ brief success line from summary)
 *   running → failed    → idle (+ failureReason error line)
 *   POST 409 (run already active) → running (NOT an error; poll that runId)
 *   POST 422/other → idle + generic error line
 *
 * Accessibility: the button carries `aria-busy` while running; the spinner glyph is aria-hidden;
 * an aria-live="polite" region announces state + progress transitions to screen readers.
 */
type ButtonState = 'idle' | 'running' | 'error';

@Component({
  selector: 'app-ingestion-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ingestion" data-testid="ingestion">
      <button
        class="btn"
        type="button"
        data-testid="start-ingestion-btn"
        [disabled]="isRunning()"
        [attr.aria-busy]="isRunning()"
        (click)="start()"
      >
        @if (isRunning()) {
          <span class="spinner" aria-hidden="true"></span>
          <span>Ingesting…</span>
        } @else {
          <span>Start ingestion</span>
        }
      </button>

      @if (isRunning() && progress(); as p) {
        <span class="progress" data-testid="ingestion-progress">
          {{ p.alarmsEmitted }} / {{ p.alarmsTotal }} alarms
        </span>
      }

      @if (successLine(); as s) {
        <span class="success" data-testid="ingestion-success">{{ s }}</span>
      }

      @if (errorLine(); as e) {
        <span class="error" role="alert" data-testid="ingestion-error">{{ e }}</span>
      }

      <!-- SR-only live region: announces state/progress changes without cluttering the layout. -->
      <span class="visually-hidden" aria-live="polite" data-testid="ingestion-status-live">
        {{ liveMessage() }}
      </span>
    </div>
  `,
  styles: [
    `
      .ingestion {
        display: inline-flex;
        align-items: center;
        gap: 0.6rem;
        flex-wrap: wrap;
      }
      .btn {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
      }
      .spinner {
        width: 0.95rem;
        height: 0.95rem;
        border: 2px solid currentColor;
        border-right-color: transparent;
        border-radius: 50%;
        display: inline-block;
        animation: ingestion-spin 0.7s linear infinite;
      }
      @keyframes ingestion-spin {
        to {
          transform: rotate(360deg);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .spinner {
          animation-duration: 2s;
        }
      }
      .progress {
        font-size: 0.85rem;
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
      }
      .success {
        font-size: 0.85rem;
        color: var(--text-muted);
      }
      .error {
        font-size: 0.85rem;
        color: var(--error-text, var(--error));
        font-weight: 600;
      }
    `,
  ],
})
export class IngestionButtonComponent implements OnInit {
  private readonly sim = inject(SimulatorClient);
  private readonly config = inject(ApiConfigService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly state = signal<ButtonState>('idle');
  private readonly status = signal<SynthRunStatus | null>(null);
  readonly progress = signal<SynthProgress | null>(null);
  private readonly summary = signal<SynthSummaryModel | null>(null);
  private readonly errorMessage = signal<string | null>(null);

  /** Poll cadence: reuse the app's streaming interval, clamped to the spec's ~1.5–2s window. */
  private readonly pollMs = Math.min(2000, Math.max(1500, this.config.streamingRefreshIntervalMs));
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly isRunning = computed(() => this.state() === 'running');

  readonly errorLine = computed(() => (this.state() === 'error' ? this.errorMessage() : null));

  readonly successLine = computed(() => {
    if (this.state() !== 'idle' || this.status() !== 'completed') {
      return null;
    }
    const s = this.summary();
    return s ? `${s.alarmsEmitted} alarms emitted` : 'Ingestion complete';
  });

  readonly liveMessage = computed(() => {
    if (this.state() === 'running') {
      const p = this.progress();
      const counts = p ? ` — ${p.alarmsEmitted} of ${p.alarmsTotal} alarms emitted` : '';
      return `Ingestion running${counts}. Alarms will appear in the Alarms tab.`;
    }
    if (this.state() === 'error') {
      return this.errorMessage() ?? 'Ingestion error.';
    }
    if (this.status() === 'completed') {
      return this.successLine() ?? 'Ingestion complete.';
    }
    return 'Ready to start ingestion.';
  });

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.stopPolling());
    // Reflect an already-running run started elsewhere immediately on load.
    this.sim.getStatus().subscribe({
      next: (s) => {
        this.applyStatus(s.status, s.progress, s.summary);
        if (s.status === 'running') {
          this.state.set('running');
          this.schedulePoll();
        }
      },
      error: () => {
        /* graceful degrade — button stays idle; a real click will surface any hard error */
      },
    });
  }

  start(): void {
    if (this.isRunning()) {
      return;
    }
    this.errorMessage.set(null);
    this.summary.set(null);
    this.status.set(null);
    // Optimistic: enter running immediately so the click is responsive.
    this.state.set('running');
    this.sim.startRun({}).subscribe({
      next: () => {
        this.schedulePoll();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // A run is already active — this is not a failure: switch straight into polling.
          this.schedulePoll();
          return;
        }
        this.state.set('error');
        this.errorMessage.set(
          err.status === 422
            ? 'Could not start ingestion: the request was invalid.'
            : 'Could not start ingestion. Please try again.',
        );
      },
    });
  }

  private schedulePoll(): void {
    this.stopPolling();
    // Poll immediately, then on a fixed cadence until a terminal state.
    this.pollOnce();
    this.timer = setTimeout(() => this.schedulePoll(), this.pollMs);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private pollOnce(): void {
    this.sim.getStatus().subscribe({
      next: (s) => this.applyStatus(s.status, s.progress, s.summary),
      error: () => {
        /* keep polling; a transient error should not abort the spinner */
      },
    });
  }

  private applyStatus(
    status: SynthRunStatus,
    progress: SynthProgress,
    summary: SynthSummaryModel | null,
  ): void {
    this.status.set(status);
    this.progress.set(progress);
    this.summary.set(summary);

    if (status === 'completed') {
      this.stopPolling();
      this.state.set('idle');
    } else if (status === 'failed') {
      this.stopPolling();
      this.state.set('error');
      this.errorMessage.set(summary?.failureReason ?? 'Ingestion failed.');
    }
    // 'running' and 'idle' leave the current state as-is (idle status after a click is the brief
    // pre-start window; the running state is held by start()/ngOnInit until a terminal status).
  }
}
