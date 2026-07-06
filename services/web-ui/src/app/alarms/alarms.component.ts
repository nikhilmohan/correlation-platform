import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AlarmsStore } from './alarms.store';
import { AlarmSummary, LifecycleState } from '../api/models';
import { alarmTypeLabel } from '../patterns/alarm-type-labels';
import { relativeTime } from '../core/relative-time';

/**
 * Unified 'Alarms' view (Part 3). The SINGLE place for live alarm state — replaces the old Streaming
 * table AND the Stats Incidents + Alarm-lifecycle tabs. Layout:
 *   - a compact KPI header strip (auto-correlation %, alarm-reduction ratio, RCA accuracy, live
 *     incident count, alarms processed) — the same numbers as the dashboard/stats, and
 *   - ONE formatted alarm table sourced from the Alarm Manager `/alarms`, TIMESTAMP-first and sorted
 *     DESCENDING (most recent first). Columns: Timestamp, Severity, Alarm type, Managed object, State
 *     (lifecycle), Correlation/Incident.
 *
 * Correlation model: a root-cause alarm row is visually highlighted (accent + ★ RCA badge) and
 * carries a clickable incident icon (data-testid="alarm-incident-link") to `/incidents/:incidentId`;
 * its correlated child alarms are nested/indented and expandable via a toggle on the RCA row.
 * Uncorrelated alarms are plain rows interleaved by timestamp. Severity is X.733 colour-coded via a
 * left border + a coloured severity pill so a NOC engineer can scan by severity in light + dark.
 */
@Component({
  selector: 'app-alarms',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AlarmsStore],
  imports: [DatePipe, DecimalPipe, PercentPipe, RouterLink],
  template: `
    <h1>Alarms</h1>

    <!-- KPI HEADER STRIP — small, scannable. Mirrors the dashboard/stats numbers. -->
    <section class="card kpis" aria-label="Alarm and correlation KPIs">
      <span class="kpi" data-testid="kpi-autocorr">
        <span class="kpi-label">Auto-correlation</span>
        <span class="kpi-value">
          @if (store.autoCorrelationPct() !== null) {
            {{ store.autoCorrelationPct()! | percent: '1.1-1' }}
          } @else {
            N/A
          }
        </span>
      </span>
      <span class="kpi" data-testid="kpi-reduction">
        <span class="kpi-label">Alarm reduction</span>
        <span class="kpi-value">
          @if (store.alarmReductionRatio() !== null) {
            {{ store.alarmReductionRatio() | number: '1.1-1' }} : 1
          } @else {
            N/A
          }
        </span>
      </span>
      <span class="kpi" data-testid="kpi-rca">
        <span class="kpi-label">RCA accuracy</span>
        <span class="kpi-value">
          @if (store.rcaAccuracy().value !== null) {
            {{ store.rcaAccuracy().value! | percent: '1.0-1' }}
          } @else {
            N/A
          }
        </span>
      </span>
      <span class="kpi" data-testid="kpi-incidents">
        <span class="kpi-label">Live incidents</span>
        <span class="kpi-value">{{ store.liveIncidentCount() }}</span>
      </span>
      <span class="kpi" data-testid="kpi-processed">
        <span class="kpi-label">Alarms processed</span>
        <span class="kpi-value">{{ store.alarmsProcessed() }}</span>
      </span>
    </section>

    <div class="toolbar">
      <label>
        Lifecycle state
        <select data-testid="alarm-filter" (change)="onFilter($event)">
          <option value="all">all</option>
          <option value="open">open</option>
          <option value="in-progress">in-progress</option>
          <option value="correlated">correlated</option>
          <option value="cleared">cleared</option>
        </select>
      </label>
      <span class="legend" aria-hidden="true">
        <span class="sev-pill sev-critical">critical</span>
        <span class="sev-pill sev-major">major</span>
        <span class="sev-pill sev-minor">minor</span>
        <span class="sev-pill sev-warning">warning</span>
        <span class="sev-pill sev-cleared">cleared</span>
      </span>
    </div>

    <section class="card table-card" aria-labelledby="alarms-h">
      <h2 id="alarms-h" class="visually-hidden">Live alarms</h2>
      <table class="alarm-table">
        <caption class="visually-hidden">
          Live alarms sorted most-recent first. Root-cause alarms are highlighted and group their
          correlated child alarms.
        </caption>
        <thead>
          <tr>
            <th scope="col">Timestamp</th>
            <th scope="col">Severity</th>
            <th scope="col">Alarm type</th>
            <th scope="col">Managed object</th>
            <th scope="col">State</th>
            <th scope="col">Correlation</th>
          </tr>
        </thead>
        <tbody>
          @for (row of store.rows(); track row.alarm.alarmId) {
            <!-- RCA / plain alarm row -->
            <tr
              data-testid="alarm-row"
              [attr.data-role]="row.kind === 'rca' ? 'root-cause' : 'none'"
              [class.rca-row]="row.kind === 'rca'"
              [class]="'sev-border-' + sevKey(row.alarm)"
            >
              <td class="ts" data-testid="alarm-raised-at" [title]="rel(row.alarm.raisedAt)">
                {{ row.alarm.raisedAt ? (row.alarm.raisedAt | date: TS_FMT) : '—' }}
              </td>
              <td>
                <span class="sev-pill" [class]="'sev-' + sevKey(row.alarm)" data-testid="alarm-severity">
                  {{ sevLabel(row.alarm) }}
                </span>
              </td>
              <td class="type-cell">
                @if (row.kind === 'rca') {
                  @if (row.children.length) {
                    <button
                      type="button"
                      class="expander"
                      data-testid="alarm-expand"
                      [attr.aria-expanded]="isExpanded(row.incidentId!)"
                      [attr.aria-label]="
                        (isExpanded(row.incidentId!) ? 'Collapse' : 'Expand') +
                        ' ' + row.children.length + ' correlated child alarms'
                      "
                      (click)="toggle(row.incidentId!)"
                    >
                      <span aria-hidden="true">{{ isExpanded(row.incidentId!) ? '▾' : '▸' }}</span>
                    </button>
                  }
                  <span class="rca-badge" aria-label="Root cause alarm" data-testid="rca-badge">&#9733; RCA</span>
                }
                <span class="alarm-type">{{ label(row.alarm) }}</span>
              </td>
              <td class="mo" [title]="row.alarm.managedObjectId">{{ row.alarm.managedObjectId }}</td>
              <td>
                <span class="badge state" [class]="stateClass(row.alarm.lifecycleState)" data-testid="lifecycle-state">
                  {{ row.alarm.lifecycleState }}
                </span>
              </td>
              <td>
                @if (row.incidentId) {
                  <a
                    class="incident-link"
                    data-testid="alarm-incident-link"
                    [routerLink]="['/incidents', row.incidentId]"
                    [attr.aria-label]="'Open incident ' + row.incidentId"
                    [title]="'Open incident ' + row.incidentId"
                  >
                    <span aria-hidden="true">&#9432;</span>
                    <span class="inc-id">{{ row.incidentId }}</span>
                    @if (row.children.length) {
                      <span class="child-count">+{{ row.children.length }}</span>
                    }
                  </a>
                } @else {
                  <span class="muted">—</span>
                }
              </td>
            </tr>

            <!-- Nested correlated child alarms (grouped under the RCA row, expandable). -->
            @if (row.kind === 'rca' && isExpanded(row.incidentId!)) {
              @for (c of row.children; track c.alarmId) {
                <tr
                  data-testid="alarm-row"
                  data-role="child"
                  class="child-row"
                  [class]="'sev-border-' + sevKey(c)"
                >
                  <td class="ts" data-testid="alarm-raised-at" [title]="rel(c.raisedAt)">
                    {{ c.raisedAt ? (c.raisedAt | date: TS_FMT) : '—' }}
                  </td>
                  <td>
                    <span class="sev-pill" [class]="'sev-' + sevKey(c)" data-testid="alarm-severity">{{ sevLabel(c) }}</span>
                  </td>
                  <td class="type-cell">
                    <span class="tree" aria-hidden="true">&#9492;&#9472;</span>
                    <span class="alarm-type">{{ label(c) }}</span>
                  </td>
                  <td class="mo" [title]="c.managedObjectId">{{ c.managedObjectId }}</td>
                  <td>
                    <span class="badge state" [class]="stateClass(c.lifecycleState)" data-testid="lifecycle-state">
                      {{ c.lifecycleState }}
                    </span>
                  </td>
                  <td class="muted">child</td>
                </tr>
              }
            }
          }
        </tbody>
      </table>
      @if (!store.rows().length) {
        <p class="empty-state">No alarms.</p>
      }
    </section>
  `,
  styles: [
    `
      .kpis {
        display: flex;
        gap: 1.75rem;
        flex-wrap: wrap;
        margin-bottom: 1rem;
        padding: 0.75rem 1rem;
      }
      .kpi {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .kpi-label {
        color: var(--text-muted);
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        font-weight: 600;
      }
      .kpi-value {
        font-size: 1.2rem;
        font-weight: 700;
        line-height: 1.1;
      }
      .toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 1rem;
        margin-bottom: 0.6rem;
      }
      .toolbar label {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
      }
      select {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .legend {
        display: inline-flex;
        gap: 0.35rem;
        flex-wrap: wrap;
      }
      .table-card {
        padding: 0;
        overflow: hidden;
      }
      .alarm-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.9rem;
      }
      .alarm-table th,
      .alarm-table td {
        text-align: left;
        padding: 0.5rem 0.75rem;
        border-bottom: 1px solid var(--border);
        vertical-align: middle;
      }
      .alarm-table thead th {
        background: var(--surface-2);
        color: var(--text-muted);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        position: sticky;
        top: 0;
      }
      /* Timestamp column: fixed-width monospace with tabular numerals so rows line up. */
      .ts {
        white-space: nowrap;
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace;
        font-variant-numeric: tabular-nums;
        font-size: 0.82rem;
        color: var(--text-muted);
        cursor: default;
      }
      .mo {
        color: var(--text-muted);
        font-size: 0.85rem;
        max-width: 16rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .type-cell {
        display: flex;
        align-items: center;
        gap: 0.4rem;
      }
      /* Severity colour-coding — left border on the row + a coloured pill. X.733 palette. */
      tr[class*='sev-border-'] td:first-child {
        border-left: 4px solid transparent;
      }
      .sev-border-critical td:first-child {
        border-left-color: #ef4444;
      }
      .sev-border-major td:first-child {
        border-left-color: #f97316;
      }
      .sev-border-minor td:first-child {
        border-left-color: #eab308;
      }
      .sev-border-warning td:first-child {
        border-left-color: #3b82f6;
      }
      .sev-border-cleared td:first-child {
        border-left-color: #94a3b8;
      }
      .sev-pill {
        display: inline-block;
        padding: 0.05rem 0.5rem;
        border-radius: 999px;
        font-size: 0.72rem;
        font-weight: 700;
        text-transform: capitalize;
        color: #fff;
      }
      .sev-critical {
        background: #dc2626;
      }
      .sev-major {
        background: #ea580c;
      }
      .sev-minor {
        background: #ca8a04;
      }
      .sev-warning {
        background: #2563eb;
      }
      .sev-cleared {
        background: #64748b;
      }
      /* RCA row highlight: accent tint + bold type + the ★ RCA badge. */
      .rca-row {
        background: color-mix(in srgb, var(--accent) 12%, transparent);
      }
      .rca-row .alarm-type {
        font-weight: 700;
      }
      .rca-badge {
        display: inline-block;
        padding: 0.05rem 0.4rem;
        border-radius: 6px;
        background: var(--accent-strong);
        color: var(--on-accent);
        font-size: 0.7rem;
        font-weight: 700;
        white-space: nowrap;
      }
      .expander {
        border: 1px solid var(--border);
        background: var(--surface-2);
        color: var(--text);
        border-radius: 5px;
        cursor: pointer;
        line-height: 1;
        padding: 0 0.35rem;
        height: 1.4rem;
        font-size: 0.8rem;
      }
      .expander:hover {
        border-color: var(--accent);
      }
      /* Nested child alarm rows: indented + muted with a connecting tree glyph. */
      .child-row {
        background: color-mix(in srgb, var(--surface-2) 55%, transparent);
      }
      .child-row .type-cell {
        padding-left: 1.5rem;
      }
      .child-row .tree {
        color: var(--text-muted);
        font-family: monospace;
      }
      .incident-link {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        font-size: 0.85rem;
        text-decoration: none;
        color: var(--accent);
      }
      .incident-link:hover {
        text-decoration: underline;
      }
      .incident-link span[aria-hidden] {
        font-size: 1rem;
      }
      .inc-id {
        font-family: monospace;
        font-size: 0.8rem;
      }
      .child-count {
        background: var(--surface-2);
        border: 1px solid var(--border);
        border-radius: 999px;
        padding: 0 0.35rem;
        font-size: 0.7rem;
        color: var(--text-muted);
      }
      .badge.state {
        text-transform: lowercase;
        padding: 0.05rem 0.5rem;
        border-radius: 6px;
        font-size: 0.75rem;
      }
      .state-correlated {
        background: var(--accent);
        color: var(--on-accent);
      }
      .state-cleared {
        background: var(--ok);
        color: #06280f;
      }
      .state-in-progress {
        background: var(--warn);
        color: #3a2a00;
      }
      .state-open {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
      }
      .muted {
        color: var(--text-muted);
      }
      .empty-state {
        padding: 1rem;
      }
    `,
  ],
})
export class AlarmsComponent implements OnInit {
  readonly store = inject(AlarmsStore);

  /** Incidents whose child alarms are expanded (default collapsed). Keyed by incidentId. */
  private readonly expanded = signal<ReadonlySet<string>>(new Set());

  /**
   * Compact, unambiguous absolute-timestamp format: `dd MMM yy HH:mm:ss.SSS` (day, short month,
   * 2-digit year, 24h time WITH milliseconds — alarms can be sub-second apart). The relative
   * "… ago" form is kept only as the hover title.
   */
  readonly TS_FMT = 'dd MMM yy HH:mm:ss.SSS';

  /** X.733 perceived-severity → colour-key (drives the left-border + pill class). */
  private static readonly SEV_KEYS = new Set(['critical', 'major', 'minor', 'warning', 'cleared']);

  readonly rowCount = computed(() => this.store.rows().length);

  ngOnInit(): void {
    this.store.loadAll();
  }

  onFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as LifecycleState | 'all';
    this.store.setStateFilter(value);
  }

  isExpanded(incidentId: string): boolean {
    return this.expanded().has(incidentId);
  }

  toggle(incidentId: string): void {
    const next = new Set(this.expanded());
    if (next.has(incidentId)) {
      next.delete(incidentId);
    } else {
      next.add(incidentId);
    }
    this.expanded.set(next);
  }

  /** Readable alarm-type label, preferring `alarmType` then falling back to `eventType`. */
  label(a: AlarmSummary): string {
    return alarmTypeLabel(a.alarmType ?? a.eventType);
  }

  /**
   * Severity colour-key from `perceivedSeverity` (X.733). `indeterminate`/unknown map to the neutral
   * grey `cleared` key so the row is never uncoloured.
   */
  sevKey(a: AlarmSummary): string {
    const s = (a.perceivedSeverity ?? '').toLowerCase();
    return AlarmsComponent.SEV_KEYS.has(s) ? s : 'cleared';
  }

  /** Severity label for the pill (the raw perceived severity, or 'unknown' when absent). */
  sevLabel(a: AlarmSummary): string {
    return (a.perceivedSeverity ?? 'unknown').toLowerCase();
  }

  /** Tone class for the lifecycle-state badge. */
  stateClass(state: LifecycleState): string {
    return `state-${state}`;
  }

  /** Relative "… ago" form for the timestamp hover title. */
  rel(iso: string | null | undefined): string {
    return relativeTime(iso);
  }
}
