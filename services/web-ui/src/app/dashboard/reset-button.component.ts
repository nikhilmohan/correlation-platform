import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { TopologyStore } from '../topology/topology.store';
import { DashboardStore } from './dashboard.store';
import { DashboardActionsService } from './dashboard-actions.service';

/**
 * "Reset" button (dashboard, beside "Start ingestion"). Purges all P3 LIVE alarm + correlation state
 * so the topology (geo map site pins + site-graph device nodes, coloured red/amber/green by active
 * alarm severity) progressively returns to healthy — red → amber → green as alarms clear — ending
 * ALL-GREEN when the reset completes. P1 (topology) and P2 (noise-filter / patterns / codebook) data
 * is untouched and keeps reflecting as-is (the two purge endpoints only clear live-alarm +
 * correlation-session state).
 *
 * Flow on click:
 *   1. Enter `resetting` (spinner + "Resetting…", disabled, aria-busy). Publishes to the shared
 *      DashboardActionsService so "Start ingestion" is disabled too (they must not run concurrently).
 *   2. Fire BOTH purges concurrently (forkJoin):
 *        POST /api/alarm-manager/admin/purge-live-alarms   (clears the alarms that COLOUR the topology)
 *        POST /api/correlation-engine/admin/reset-correlation (clears incidents + in-memory session)
 *   3. After both return 200, POLL: re-pull the shared alarm snapshot via TopologyStore.refreshAlarms()
 *      every ~pollMs and check the active-alarm count. The purge is instant server-side, so the count
 *      should drop to 0 quickly; keep spinning until it reads 0 (all green), then stop. A safety
 *      timeout stops the spinner regardless (a fresh ingestion may have started).
 *   4. Each refresh re-publishes the shared `alarms` signal → the geo map + site graph re-colour
 *      reactively (red → amber → green) as the count falls.
 *   5. On 0 active alarms: back to "Reset" idle (aria-busy=false); refresh the CE stats (KPI header
 *      resets to 0 / N/A).
 *   6. Error (either POST fails): stop spinning, inline error (reset-error), re-enable buttons.
 *
 * Accessibility: aria-busy on the button while resetting; an aria-live="polite" region announces
 * "Resetting…" / "Reset complete"; the spinner glyph is aria-hidden.
 */
type ResetState = 'idle' | 'resetting' | 'done' | 'error';

@Component({
  selector: 'app-reset-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="reset" data-testid="reset">
      <button
        class="btn btn-secondary"
        type="button"
        data-testid="reset-btn"
        [disabled]="isResetting() || actions.ingesting()"
        [attr.aria-busy]="isResetting()"
        (click)="reset()"
      >
        @if (isResetting()) {
          <span class="spinner" aria-hidden="true"></span>
          <span>Resetting…</span>
        } @else {
          <span>Reset</span>
        }
      </button>

      @if (doneLine(); as d) {
        <span class="done" data-testid="reset-done">{{ d }}</span>
      }

      @if (errorLine(); as e) {
        <span class="error" role="alert" data-testid="reset-error">{{ e }}</span>
      }

      <!-- SR-only live region: announces reset state transitions without cluttering the layout. -->
      <span class="visually-hidden" aria-live="polite" data-testid="reset-status-live">
        {{ liveMessage() }}
      </span>
    </div>
  `,
  styles: [
    `
      .reset {
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
        animation: reset-spin 0.7s linear infinite;
      }
      @keyframes reset-spin {
        to {
          transform: rotate(360deg);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .spinner {
          animation-duration: 2s;
        }
      }
      .done {
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
export class ResetButtonComponent {
  private readonly am = inject(AlarmManagerClient);
  private readonly ce = inject(CorrelationEngineClient);
  private readonly topology = inject(TopologyStore);
  private readonly dashboard = inject(DashboardStore, { optional: true });
  readonly actions = inject(DashboardActionsService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly state = signal<ResetState>('idle');
  private readonly errorMessage = signal<string | null>(null);

  /** Poll cadence for the re-colour-to-green loop (spec ~1–1.5s). */
  private readonly pollMs = 1200;
  /** Hard safety bound: stop spinning after this long even if alarms remain (e.g. fresh ingestion). */
  private readonly safetyMs = 30_000;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private safetyTimer: ReturnType<typeof setTimeout> | null = null;

  readonly isResetting = () => this.state() === 'resetting';
  readonly errorLine = () => (this.state() === 'error' ? this.errorMessage() : null);
  readonly doneLine = () => (this.state() === 'done' ? 'Reset complete' : null);

  readonly liveMessage = () => {
    switch (this.state()) {
      case 'resetting':
        return 'Resetting live alarms and correlation state. The topology is returning to healthy.';
      case 'done':
        return 'Reset complete. The topology is all clear.';
      case 'error':
        return this.errorMessage() ?? 'Reset failed.';
      default:
        return 'Ready to reset.';
    }
  };

  constructor() {
    this.destroyRef.onDestroy(() => this.stopTimers());
  }

  reset(): void {
    if (this.isResetting()) {
      return;
    }
    this.errorMessage.set(null);
    this.state.set('resetting');
    this.actions.resetting.set(true);

    // Fire BOTH purges concurrently. purgeLiveAlarms clears what COLOURS the topology; resetCorrelation
    // clears incidents + the in-memory session so the KPIs go to 0. Both are idempotent 200s.
    forkJoin({
      purge: this.am.purgeLiveAlarms(),
      reset: this.ce.resetCorrelation(),
    }).subscribe({
      next: () => {
        // Purges applied server-side (instant). Poll the alarm snapshot until it reads 0 active.
        this.startSafetyTimeout();
        this.pollUntilGreen();
      },
      error: (err: HttpErrorResponse) => this.fail(err),
    });
  }

  /**
   * Re-pull the shared alarm snapshot and, once the active-alarm count is 0 (all green), finish.
   * Otherwise schedule the next poll. Re-pulling publishes TopologyStore.alarms → the geo map + site
   * graph re-colour reactively (red → amber → green) as the count drops.
   */
  private pollUntilGreen(): void {
    // Trigger the shared refresh the topology views colour off. When it completes we read the count.
    this.topology.refreshAlarms();
    // The refresh is async; check the snapshot on the next tick, and reschedule if not yet green. We
    // gate on the store's own loading flag so we read a settled snapshot, not a mid-flight empty one.
    this.timer = setTimeout(() => this.checkGreenOrReschedule(), this.pollMs);
  }

  private checkGreenOrReschedule(): void {
    if (this.state() !== 'resetting') {
      return; // superseded (safety timeout fired, or destroyed).
    }
    if (this.topology.alarmsLoading()) {
      // A refresh is still settling — wait one more cadence and re-check the count.
      this.timer = setTimeout(() => this.checkGreenOrReschedule(), this.pollMs);
      return;
    }
    if (this.topology.alarms().length === 0) {
      this.finish();
      return;
    }
    // Still faulted rows present — pull again and keep spinning (safety timeout is the escape hatch).
    this.pollUntilGreen();
  }

  /** Successful completion: all green. Refresh the KPI header so the stats reset to 0 / N/A. */
  private finish(): void {
    this.stopTimers();
    this.state.set('done');
    this.actions.resetting.set(false);
    // Refresh the CE-backed KPI header (auto-correlation, alarm-reduction, RCA, live incidents → 0).
    this.dashboard?.load();
  }

  private fail(err: HttpErrorResponse): void {
    this.stopTimers();
    this.state.set('error');
    this.actions.resetting.set(false);
    const status = err?.status;
    this.errorMessage.set(
      status && status > 0
        ? `Reset failed (HTTP ${status}). Please try again.`
        : 'Reset failed: a service is unreachable. Please try again.',
    );
  }

  /** Safety bound: stop spinning after safetyMs even if alarms remain (e.g. a fresh ingestion). */
  private startSafetyTimeout(): void {
    this.safetyTimer = setTimeout(() => {
      if (this.state() === 'resetting') {
        // Stop gracefully — surface completion; the header still refreshes so KPIs reflect reality.
        this.finish();
      }
    }, this.safetyMs);
  }

  private stopTimers(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.safetyTimer !== null) {
      clearTimeout(this.safetyTimer);
      this.safetyTimer = null;
    }
  }
}
