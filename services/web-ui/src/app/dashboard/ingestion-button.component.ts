import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { DashboardActionsService } from './dashboard-actions.service';

/**
 * "Start ingestion" button (dashboard). Thin view over the shared DashboardActionsService, which
 * owns the single ingest implementation (POST /synth/run + poll /synth/status) shared with the
 * top-nav Actions menu. This component only renders the coordinator's ingest state.
 *
 * Accessibility: the button carries `aria-busy` while running; the spinner glyph is aria-hidden;
 * an aria-live="polite" region announces state + progress transitions to screen readers.
 */
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
        [disabled]="disabled()"
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

      @if (isRunning() && actions.ingestProgress(); as p) {
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
  readonly actions = inject(DashboardActionsService);

  readonly isRunning = this.actions.isIngesting;
  // Disabled while THIS action runs, or while another action holds the coordinator (mutual exclusion).
  readonly disabled = computed(() => this.actions.busy());

  readonly errorLine = computed(() =>
    this.actions.ingest().status === 'error' ? this.actions.ingest().message : null,
  );

  readonly successLine = computed(() =>
    this.actions.ingest().status === 'done' ? this.actions.ingest().message : null,
  );

  readonly liveMessage = computed(() => {
    const st = this.actions.ingest();
    if (st.status === 'running') {
      const p = this.actions.ingestProgress();
      const counts = p ? ` — ${p.alarmsEmitted} of ${p.alarmsTotal} alarms emitted` : '';
      return `Ingestion running${counts}. Alarms will appear in the Alarms tab.`;
    }
    if (st.status === 'error') {
      return st.message ?? 'Ingestion error.';
    }
    if (st.status === 'done') {
      return st.message ?? 'Ingestion complete.';
    }
    return 'Ready to start ingestion.';
  });

  ngOnInit(): void {
    this.actions.initFromServer();
  }

  start(): void {
    this.actions.startIngest();
  }
}
