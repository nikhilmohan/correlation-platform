import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ChatterStore } from './chatter.store';
import { ErrorBannerService } from '../core/error-banner.service';

/**
 * Chatter management (FIX F-UI1, AC 55-56). Left pane: NF observed-chatter ranked by
 * occurrenceCount, marked promoted/candidate. Right pane: current Enrichment chatter for the
 * selected source. Promote/remove drives the closed loop (NF learned noise → Enrichment live).
 */
@Component({
  selector: 'app-chatter-management',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ChatterStore],
  template: `
    <h1>Chatter management</h1>
    <p class="muted">
      NF learned noise → operator review/promote → Enrichment applies it live (the live path then
      suppresses promoted chatter).
    </p>

    <label class="source">
      source
      <select data-testid="source-select" (change)="onSource($event)">
        <option value="nms-alpha" [selected]="store.selectedSource() === 'nms-alpha'">nms-alpha</option>
        <option value="default" [selected]="store.selectedSource() === 'default'">default</option>
      </select>
    </label>

    @if (errors.forService('Noise Filter'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }
    @if (errors.forService('Enrichment'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    <div class="grid">
      <section class="card" aria-labelledby="obs-h">
        <h2 id="obs-h">Observed chatter (NF, ranked by count)</h2>
        @if (store.joinView().length) {
          <table>
            <caption class="visually-hidden">Observed chatter candidates ranked by occurrence</caption>
            <thead>
              <tr><th scope="col">Managed object</th><th scope="col">Type</th><th scope="col">Count</th><th scope="col">Status</th><th scope="col">Action</th></tr>
            </thead>
            <tbody>
              @for (row of store.joinView(); track trackRow(row)) {
                <tr data-testid="observed-row" [attr.data-status]="row.status">
                  <td>{{ row.observed.managedObjectId ?? 'source-level' }}</td>
                  <td>{{ row.observed.eventType }}</td>
                  <td>{{ row.observed.occurrenceCount }}</td>
                  <td data-testid="observed-status">{{ row.status }}</td>
                  <td>
                    @if (!row.alreadyPromoted) {
                      <button
                        class="btn"
                        type="button"
                        data-testid="promote-btn"
                        [disabled]="store.isPending(row.observed.managedObjectId, row.observed.eventType)"
                        (click)="store.promote(row.observed)"
                      >
                        Promote
                      </button>
                    } @else {
                      <span class="badge badge-new">promoted</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="empty-state">No observed chatter.</p>
        }
      </section>

      <section class="card" aria-labelledby="enr-h">
        <h2 id="enr-h">Enrichment chatter list</h2>
        @if (store.enrichmentChatter().length) {
          <table>
            <caption class="visually-hidden">Current Enrichment known-chatter list</caption>
            <thead>
              <tr><th scope="col">Managed object</th><th scope="col">Type</th><th scope="col">Action</th></tr>
            </thead>
            <tbody>
              @for (entry of store.enrichmentChatter(); track entry.managedObjectId + entry.eventType) {
                <tr data-testid="enrichment-row">
                  <td>{{ entry.managedObjectId ?? 'source-level' }}</td>
                  <td>{{ entry.eventType }}</td>
                  <td>
                    <button
                      class="btn btn-secondary"
                      type="button"
                      data-testid="remove-btn"
                      [disabled]="store.isPending(entry.managedObjectId, entry.eventType)"
                      (click)="store.remove(entry)"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="empty-state">No chatter entries for this source.</p>
        }
      </section>
    </div>
  `,
  styles: [
    `
      .muted {
        color: var(--text-muted);
      }
      .source {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
        margin: 0.6rem 0;
      }
      .source select {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 1rem;
      }
      @media (max-width: 800px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class ChatterManagementComponent implements OnInit {
  readonly store = inject(ChatterStore);
  readonly errors = inject(ErrorBannerService);
  private readonly route = inject(ActivatedRoute);

  ngOnInit(): void {
    const source = this.route.snapshot.queryParamMap.get('source');
    if (source) {
      this.store.selectedSource.set(source);
    }
    this.store.load();
  }

  onSource(event: Event): void {
    this.store.selectSource((event.target as HTMLSelectElement).value);
  }

  trackRow(row: { observed: { managedObjectId: string | null; eventType: string } }): string {
    return `${row.observed.managedObjectId}-${row.observed.eventType}`;
  }
}
