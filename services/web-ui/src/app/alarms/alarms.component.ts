import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AlarmsStore } from './alarms.store';
import { LivePollingService } from '../streaming/live-polling.service';
import { DeltaDiffService } from '../streaming/delta-diff';
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
  providers: [AlarmsStore, LivePollingService, DeltaDiffService],
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
      <div class="toolbar-left">
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
        <button
          type="button"
          class="expand-all"
          data-testid="alarm-expand-all"
          [attr.aria-pressed]="allExpanded()"
          (click)="toggleExpandAll()"
        >
          {{ allExpanded() ? 'Collapse all' : 'Expand all' }}
        </button>
      </div>

      <div class="toolbar-right">
        <!-- LIVE indicator + pause/resume. Reuses LivePollingService.autoRefresh. -->
        <span
          class="live"
          data-testid="live-indicator"
          [class.paused]="!live.autoRefresh()"
          [class.stale]="live.pollError()"
          role="status"
          [attr.aria-label]="liveLabel()"
          [title]="liveLabel()"
        >
          <span class="dot" aria-hidden="true"></span>
          @if (live.pollError()) {
            stale
          } @else if (live.autoRefresh()) {
            live
          } @else {
            paused
          }
        </span>
        <button
          type="button"
          class="live-toggle"
          data-testid="live-toggle"
          [attr.aria-pressed]="live.autoRefresh()"
          [attr.aria-label]="live.autoRefresh() ? 'Pause live updates' : 'Resume live updates'"
          (click)="toggleLive()"
        >
          <span aria-hidden="true">{{ live.autoRefresh() ? '⏸' : '▶' }}</span>
          {{ live.autoRefresh() ? 'Pause' : 'Resume' }}
        </button>
      </div>
    </div>

    <div class="legends">
      <span class="legend" aria-hidden="true">
        <span class="sev-pill sev-critical">critical</span>
        <span class="sev-pill sev-major">major</span>
        <span class="sev-pill sev-minor">minor</span>
        <span class="sev-pill sev-warning">warning</span>
        <span class="sev-pill sev-cleared">cleared</span>
      </span>
      <!-- Lifecycle-state legend (Feature 1 info affordance). -->
      <details class="state-legend" data-testid="lifecycle-legend">
        <summary>Lifecycle states</summary>
        <dl>
          <dt>open</dt>
          <dd>raised, not yet correlating</dd>
          <dt>in-progress</dt>
          <dd>being processed in an active correlation instance</dd>
          <dt>correlated</dt>
          <dd>placed in a fired incident</dd>
          <dt>cleared</dt>
          <dd>terminal / reset (reverted to open)</dd>
        </dl>
      </details>
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
            <th scope="col" class="state-th">
              State
              <span
                class="info"
                tabindex="0"
                aria-label="Lifecycle states: open = raised, not yet correlating; in-progress = being processed in an active correlation instance; correlated = placed in a fired incident; cleared = terminal/reset."
                [title]="STATE_HELP"
                >&#9432;</span
              >
            </th>
            <th scope="col">Correlation</th>
          </tr>
        </thead>
        <tbody>
          @for (row of store.rows(); track row.alarm.alarmId) {
            <!-- INCIDENT GROUP header (rca) OR plain uncorrelated alarm row. The header keeps the
                 alarm-row testid (existing selectors) AND carries data-group + a distinct
                 alarm-group testid on the group-count marker below (Feature 1). -->
            <tr
              data-testid="alarm-row"
              [attr.data-role]="row.kind === 'rca' ? 'root-cause' : 'none'"
              [attr.data-group]="row.kind === 'rca' ? 'true' : null"
              [attr.data-incident-id]="row.incidentId"
              [class.rca-row]="row.kind === 'rca'"
              [class.group-header]="row.kind === 'rca'"
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
                  <button
                    type="button"
                    class="expander"
                    data-testid="alarm-expand"
                    [attr.aria-expanded]="isExpanded(row.incidentId!)"
                    [disabled]="!row.children.length"
                    [attr.aria-label]="
                      (isExpanded(row.incidentId!) ? 'Collapse' : 'Expand') +
                      ' incident ' + row.incidentId +
                      ' — ' + row.children.length + ' correlated alarms'
                    "
                    (click)="toggle(row.incidentId!)"
                  >
                    <span aria-hidden="true">{{ isExpanded(row.incidentId!) ? '▾' : '▸' }}</span>
                  </button>
                  <span class="rca-badge" aria-label="Root cause alarm" data-testid="rca-badge">&#9733; RCA</span>
                }
                <span class="alarm-type">{{ label(row.alarm) }}</span>
                @if (row.kind === 'rca') {
                  <span class="group-count" data-testid="alarm-group" data-group-count>
                    root cause + {{ row.children.length }} correlated
                    {{ row.children.length === 1 ? 'alarm' : 'alarms' }}
                  </span>
                }
              </td>
              <td class="mo" [title]="row.alarm.managedObjectId">{{ row.alarm.managedObjectId }}</td>
              <td>
                @if (row.kind === 'rca') {
                  <!-- GROUP-LEVEL status: the whole incident reads as 'correlated' (one pill for the
                       group, not per-child). Individual child states show only when expanded. -->
                  <span
                    class="badge state state-correlated"
                    data-testid="lifecycle-state"
                    title="This incident has fired — the group is correlated regardless of individual child states."
                  >
                    correlated
                  </span>
                } @else {
                  <span class="badge state" [class]="stateClass(row.alarm.lifecycleState)" data-testid="lifecycle-state">
                    {{ row.alarm.lifecycleState }}
                  </span>
                }
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
      .toolbar-left,
      .toolbar-right {
        display: inline-flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      .toolbar label {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
      }
      .expand-all,
      .live-toggle {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem 0.6rem;
        cursor: pointer;
        font-size: 0.82rem;
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
      }
      .expand-all:hover,
      .live-toggle:hover {
        border-color: var(--accent);
      }
      /* LIVE indicator: pulsing green dot; amber when stale; grey when paused. */
      .live {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        font-size: 0.78rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--ok, #16a34a);
      }
      .live .dot {
        width: 0.55rem;
        height: 0.55rem;
        border-radius: 999px;
        background: var(--ok, #16a34a);
        box-shadow: 0 0 0 0 color-mix(in srgb, var(--ok, #16a34a) 60%, transparent);
        animation: live-pulse 1.6s ease-out infinite;
      }
      .live.paused {
        color: var(--text-muted);
      }
      .live.paused .dot {
        background: var(--text-muted);
        animation: none;
      }
      .live.stale {
        color: var(--warn, #d97706);
      }
      .live.stale .dot {
        background: var(--warn, #d97706);
        animation: none;
      }
      @keyframes live-pulse {
        0% {
          box-shadow: 0 0 0 0 color-mix(in srgb, var(--ok, #16a34a) 55%, transparent);
        }
        70% {
          box-shadow: 0 0 0 0.45rem color-mix(in srgb, var(--ok, #16a34a) 0%, transparent);
        }
        100% {
          box-shadow: 0 0 0 0 color-mix(in srgb, var(--ok, #16a34a) 0%, transparent);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .live .dot {
          animation: none;
        }
      }
      .legends {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 1rem;
        margin-bottom: 0.6rem;
      }
      .state-legend {
        font-size: 0.8rem;
      }
      .state-legend summary {
        cursor: pointer;
        color: var(--accent);
      }
      .state-legend dl {
        display: grid;
        grid-template-columns: auto 1fr;
        gap: 0.15rem 0.6rem;
        margin: 0.4rem 0 0;
      }
      .state-legend dt {
        font-weight: 700;
      }
      .state-legend dd {
        margin: 0;
        color: var(--text-muted);
      }
      .state-th .info,
      th .info {
        cursor: help;
        color: var(--text-muted);
        margin-left: 0.25rem;
      }
      .group-count {
        color: var(--text-muted);
        font-size: 0.75rem;
        white-space: nowrap;
      }
      .expander[disabled] {
        opacity: 0.35;
        cursor: default;
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
  /**
   * The self-rescheduling poll loop (reused from the old Streaming view). Provided at the component
   * level so its `DestroyRef.onDestroy` teardown fires when the operator leaves the Alarms view — the
   * timer never runs app-wide (Feature 2).
   */
  readonly live = inject(LivePollingService);

  /**
   * Incidents whose child alarms are expanded (default collapsed). Keyed by incidentId, so the set
   * SURVIVES every poll tick — a refresh that rewrites the alarm list never collapses a group the
   * operator opened (Feature 2: preserve expand state across refreshes).
   */
  private readonly expanded = signal<ReadonlySet<string>>(new Set());

  /**
   * Compact, unambiguous absolute-timestamp format: `dd MMM yy HH:mm:ss.SSS` (day, short month,
   * 2-digit year, 24h time WITH milliseconds — alarms can be sub-second apart). The relative
   * "… ago" form is kept only as the hover title.
   */
  readonly TS_FMT = 'dd MMM yy HH:mm:ss.SSS';

  /** Screen-reader / tooltip copy for the lifecycle-state column info affordance. */
  readonly STATE_HELP =
    'open = raised, not yet correlating · in-progress = being processed in an active correlation ' +
    'instance · correlated = placed in a fired incident · cleared = terminal/reset (reverted to open)';

  /** X.733 perceived-severity → colour-key (drives the left-border + pill class). */
  private static readonly SEV_KEYS = new Set(['critical', 'major', 'minor', 'warning', 'cleared']);

  readonly rowCount = computed(() => this.store.rows().length);

  /** The incidentIds that currently render as groups (rca rows). */
  private readonly groupIds = computed<string[]>(() =>
    this.store
      .rows()
      .filter((r) => r.kind === 'rca' && r.incidentId)
      .map((r) => r.incidentId!),
  );

  /** True when every current group is expanded (drives the expand-all/collapse-all label). */
  readonly allExpanded = computed<boolean>(() => {
    const ids = this.groupIds();
    return ids.length > 0 && ids.every((id) => this.expanded().has(id));
  });

  constructor() {
    // Feed every poll snapshot into the store so rows()/KPIs/grouping update live. Refreshing inside
    // an effect keeps the render reactive; the store leaves prior data intact on an errored (null)
    // tick so the last-good view survives while the stale indicator shows.
    effect(() => {
      // Only apply once a real poll tick has landed (lastUpdated set) — never let the initial empty
      // snapshot blank the store before the first fetch resolves.
      if (this.live.lastUpdated() === null) {
        return;
      }
      this.store.applyLiveSnapshot(this.live.alarmsSnapshot(), this.live.incidentsSnapshot());
      this.store.refreshStats();
    });
  }

  ngOnInit(): void {
    // Initial one-shot load, then start the live poll loop. The first poll tick fires immediately and
    // re-populates the same signals; the effect keeps the view in sync thereafter.
    this.store.loadAll();
    this.live.start();
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

  /** Expand every group when any is collapsed, otherwise collapse all. */
  toggleExpandAll(): void {
    this.expanded.set(this.allExpanded() ? new Set() : new Set(this.groupIds()));
  }

  /** Pause/resume the live poll loop (reuses LivePollingService.autoRefresh). */
  toggleLive(): void {
    if (this.live.autoRefresh()) {
      this.live.pause();
    } else {
      this.live.resume();
    }
  }

  /** Accessible label for the live indicator. */
  liveLabel(): string {
    if (this.live.pollError()) {
      return 'Live updates paused on error — showing last known data';
    }
    return this.live.autoRefresh() ? 'Live updates on' : 'Live updates paused';
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
