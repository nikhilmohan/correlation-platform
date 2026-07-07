import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { ChatterStore, ChatterClassBar } from './chatter.store';
import { ErrorBannerService } from '../core/error-banner.service';

/**
 * Chatter management — the learn → promote → suppress loop, chart-driven. Noise Filter LEARNS
 * repetitive noise → the operator PROMOTES → Enrichment SUPPRESSES it live upstream.
 *
 * Observed chatter is aggregated into sorted horizontal BAR CHARTS grouped two ways (by alarm type
 * / by device type, view toggle). Suppression works at BOTH granularities: a class-level
 * "Suppress" fans out to individual per-object `{managedObjectId, eventType}` adds for every
 * currently-observed member (no contract change — a native class-level Enrichment rule is a future
 * enhancement); expanding a bar reveals its per-object rows with individual Promote. The right
 * pane keeps the active Enrichment suppression list with Remove.
 */
@Component({
  selector: 'app-chatter-management',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ChatterStore],
  imports: [DatePipe],
  template: `
    <h1>Chatter management</h1>
    <p class="muted">
      The Noise Filter <strong>learns</strong> repetitive noise → you <strong>promote</strong> it →
      Enrichment <strong>suppresses</strong> it live upstream. Suppressing a whole class promotes
      every one of its member objects observed right now (a fan-out of per-object rules);
      <em>a native class-level rule is a future Enrichment enhancement.</em>
    </p>

    <div class="controls">
      <label class="source">
        source
        <select data-testid="source-select" (change)="onSource($event)">
          <option value="nms-alpha" [selected]="store.selectedSource() === 'nms-alpha'">nms-alpha</option>
          <option value="default" [selected]="store.selectedSource() === 'default'">default</option>
        </select>
      </label>

      <div class="toggle" role="group" aria-label="Group observed chatter by" data-testid="chatter-groupby-toggle">
        <button
          type="button"
          class="toggle-btn"
          [class.active]="store.groupBy() === 'alarmType'"
          [attr.aria-pressed]="store.groupBy() === 'alarmType'"
          (click)="store.setGroupBy('alarmType')"
        >
          By alarm type
        </button>
        <button
          type="button"
          class="toggle-btn"
          [class.active]="store.groupBy() === 'deviceType'"
          [attr.aria-pressed]="store.groupBy() === 'deviceType'"
          (click)="store.setGroupBy('deviceType')"
        >
          By device type
        </button>
      </div>
    </div>

    @if (errors.forService('Noise Filter'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }
    @if (errors.forService('Enrichment'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    <div class="grid">
      <section class="card" aria-labelledby="obs-h">
        <h2 id="obs-h">
          Observed chatter — @if (store.groupBy() === 'alarmType') { by alarm type } @else { by device type }
        </h2>
        @if (store.classBars().length) {
          <ul
            class="bars"
            [attr.data-testid]="store.groupBy() === 'alarmType' ? 'chatter-chart-alarmtype' : 'chatter-chart-devicetype'"
          >
            @for (bar of store.classBars(); track bar.key) {
              <li class="bar-item" data-testid="chatter-bar" [attr.data-class]="bar.key">
                <div class="bar-head">
                  <button
                    type="button"
                    class="disclosure"
                    [attr.aria-expanded]="store.isExpanded(bar.key)"
                    [attr.aria-label]="
                      (store.isExpanded(bar.key) ? 'Collapse ' : 'Expand ') + bar.key + ', ' + bar.suppressableCount + ' objects'
                    "
                    (click)="store.toggleExpanded(bar.key)"
                  >
                    <span class="chev" [class.open]="store.isExpanded(bar.key)" aria-hidden="true">▸</span>
                    <span class="bar-name">{{ bar.key }}</span>
                    <span class="bar-count" data-testid="chatter-bar-count">{{ bar.totalOccurrences }}</span>
                  </button>
                  @if (bar.fullySuppressed) {
                    <span class="badge badge-new" data-testid="class-suppressed-badge">suppressed</span>
                  } @else {
                    <button
                      type="button"
                      class="btn"
                      data-testid="suppress-class-btn"
                      [disabled]="store.isClassPending(bar.key)"
                      (click)="store.suppressClass(bar)"
                    >
                      {{ store.isClassPending(bar.key) ? 'Suppressing…' : 'Suppress class — ' + suppressableRemaining(bar) + ' objects' }}
                    </button>
                  }
                </div>
                <div
                  class="bar-track"
                  role="img"
                  [attr.aria-label]="bar.key + ': ' + bar.totalOccurrences + ' occurrences across ' + bar.suppressableCount + ' objects'"
                >
                  <span class="bar-fill" [style.width.%]="barPct(bar)"></span>
                </div>

                @if (store.isExpanded(bar.key)) {
                  <table class="drill">
                    <caption class="visually-hidden">Member objects of {{ bar.key }}</caption>
                    <thead>
                      <tr>
                        <th scope="col">Managed object</th>
                        <th scope="col">Type</th>
                        <th scope="col">Count</th>
                        <th scope="col">First seen</th>
                        <th scope="col">Last seen</th>
                        <th scope="col">Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (row of bar.members; track memberKey(row)) {
                        <tr data-testid="observed-row" [attr.data-status]="row.status">
                          <td>{{ row.observed.managedObjectId ?? 'source-level' }}</td>
                          <td>{{ row.observed.eventType }}</td>
                          <td>{{ row.observed.occurrenceCount }}</td>
                          <td>{{ row.observed.firstSeen ? (row.observed.firstSeen | date: 'short') : '—' }}</td>
                          <td>{{ row.observed.lastSeen ? (row.observed.lastSeen | date: 'short') : '—' }}</td>
                          <td data-testid="observed-status">
                            @if (!row.alreadyPromoted) {
                              <button
                                class="btn btn-secondary"
                                type="button"
                                data-testid="promote-btn"
                                [disabled]="store.isPending(row.observed.managedObjectId, row.observed.eventType)"
                                (click)="store.promote(row.observed)"
                              >
                                Promote
                              </button>
                            } @else {
                              <span class="badge badge-new">suppressed</span>
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                }
              </li>
            }
          </ul>
        } @else {
          <p class="empty-state">No observed chatter.</p>
        }
      </section>

      <section class="card" aria-labelledby="enr-h">
        <h2 id="enr-h">Suppressed now (Enrichment)</h2>
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
        max-width: 70ch;
      }
      .controls {
        display: flex;
        align-items: center;
        gap: 1.2rem;
        flex-wrap: wrap;
        margin: 0.6rem 0 1rem;
      }
      .source {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
      }
      .source select {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .toggle {
        display: inline-flex;
        border: 1px solid var(--border);
        border-radius: 8px;
        overflow: hidden;
      }
      .toggle-btn {
        background: var(--surface-2);
        color: var(--text-muted);
        border: 0;
        padding: 0.4rem 0.8rem;
        font-size: 0.82rem;
        font-weight: 600;
        cursor: pointer;
      }
      .toggle-btn + .toggle-btn {
        border-left: 1px solid var(--border);
      }
      .toggle-btn.active {
        background: var(--accent);
        color: #04121f;
      }
      .toggle-btn:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: -2px;
      }
      .grid {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 1rem;
      }
      @media (max-width: 900px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
      .bars {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.9rem;
      }
      .bar-head {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        justify-content: space-between;
        flex-wrap: wrap;
      }
      .disclosure {
        display: inline-flex;
        align-items: baseline;
        gap: 0.5rem;
        background: none;
        border: 0;
        color: var(--text);
        cursor: pointer;
        padding: 0.15rem 0;
        font: inherit;
        text-align: left;
        flex: 1 1 auto;
        min-width: 0;
      }
      .disclosure:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
        border-radius: 4px;
      }
      .chev {
        display: inline-block;
        transition: transform 0.12s ease;
        color: var(--text-muted);
      }
      .chev.open {
        transform: rotate(90deg);
      }
      .bar-name {
        font-weight: 700;
      }
      .bar-count {
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
        font-weight: 600;
      }
      .bar-track {
        height: 1.5rem;
        background: var(--surface-2);
        border: 1px solid var(--border);
        border-radius: 6px;
        overflow: hidden;
        margin-top: 0.3rem;
      }
      .bar-fill {
        display: block;
        height: 100%;
        background: var(--accent);
        min-width: 2px;
      }
      .drill {
        margin-top: 0.6rem;
        width: 100%;
      }
      .drill th,
      .drill td {
        font-size: 0.82rem;
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

  /** Bar length as a % of the largest class total (guarded against a zero max). */
  barPct(bar: ChatterClassBar): number {
    const max = this.store.maxClassTotal();
    return max > 0 ? (bar.totalOccurrences / max) * 100 : 0;
  }

  /** Count of members that would actually be added by a class suppress (not-yet-suppressed). */
  suppressableRemaining(bar: ChatterClassBar): number {
    return bar.members.filter((m) => !m.alreadyPromoted).length;
  }

  memberKey(row: { observed: { managedObjectId: string | null; eventType: string } }): string {
    return `${row.observed.managedObjectId}-${row.observed.eventType}`;
  }
}
