import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { StatsStore } from './stats.store';
import { NavigationService } from '../core/navigation.service';
import { AlarmSummary, LifecycleState } from '../api/models';
import { alarmTypeLabel } from '../patterns/alarm-type-labels';
import { relativeTime } from '../core/relative-time';

type Tab = 'incidents' | 'alarms' | 'noise';

/**
 * Correlation stats module (spec tasks 14-15, AC 44-49) + noise-filter run-stats (task 4,
 * AC 18-19) as the learning sub-view. Reuses RcaAccuracyService so the shown RCA-accuracy number
 * matches the dashboard (FIX F-UI2). P3/P2 backends not yet built — graceful empty/error states.
 */
@Component({
  selector: 'app-stats',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [StatsStore],
  imports: [DatePipe, DecimalPipe, PercentPipe, RouterLink],
  template: `
    <h1>Correlation stats</h1>

    <section class="card kpis" aria-label="Stats summary">
      <span data-testid="stat-reduction">
        alarm-reduction:
        @if (store.alarmReductionRatio() !== null) {
          {{ store.alarmReductionRatio() | number: '1.1-1' }}
        } @else {
          N/A
        }
      </span>
      <span data-testid="stat-rca">
        RCA accuracy:
        @if (store.rcaAccuracy().value !== null) {
          {{ store.rcaAccuracy().value! | percent: '1.0-1' }} ({{ store.rcaAccuracy().source }})
        } @else {
          N/A
        }
      </span>
      <span data-testid="stat-autocorr">
        auto-correlation:
        @if (store.autoCorrelationPct() !== null) {
          {{ store.autoCorrelationPct()! | percent: '1.1-1' }}
        } @else {
          N/A
        }
      </span>
      <span>pattern matches: {{ store.stats()?.patternMatchCount ?? 0 }}</span>
      <span>codebook matches: {{ store.stats()?.codebookMatchCount ?? 0 }}</span>
    </section>

    <div class="tabs" role="tablist" aria-label="Stats views">
      <button type="button" role="tab" data-testid="tab-incidents" [class.active]="tab() === 'incidents'" [attr.aria-selected]="tab() === 'incidents'" (click)="setTab('incidents')">Incidents</button>
      <button type="button" role="tab" data-testid="tab-alarms" [class.active]="tab() === 'alarms'" [attr.aria-selected]="tab() === 'alarms'" (click)="setTab('alarms')">Alarm lifecycle</button>
      <button type="button" role="tab" data-testid="tab-noise" [class.active]="tab() === 'noise'" [attr.aria-selected]="tab() === 'noise'" (click)="setTab('noise')">Noise run-stats</button>
    </div>

    @switch (tab()) {
      @case ('incidents') {
        <section class="card" aria-labelledby="inc-h">
          <h2 id="inc-h">Incidents</h2>
          @if (store.sortedIncidents().length) {
            <ul class="list">
              @for (inc of store.sortedIncidents(); track inc.incidentId) {
                <li data-testid="stats-incident">
                  <a [routerLink]="['/incidents', inc.incidentId]">{{ inc.incidentId }}</a>
                  — root {{ inc.rootCauseAlarmType ?? inc.rootCauseAlarmId }}
                  · children: {{ inc.childAlarmIds.join(', ') || 'none' }}
                  @if (inc.createdAt) {
                    <span class="ts" data-testid="incident-created-at" [title]="inc.createdAt | date: 'medium'">
                      {{ rel(inc.createdAt) }}
                    </span>
                  }
                </li>
              }
            </ul>
          } @else {
            <p class="empty-state">No incidents.</p>
          }
        </section>
      }
      @case ('alarms') {
        <section class="card" aria-labelledby="alarm-h">
          <h2 id="alarm-h">Alarm lifecycle</h2>
          <p class="hint">Alarms grouped by correlation. Each incident's root cause is highlighted; correlated child alarms are nested below it.</p>
          <label>
            filter state
            <select data-testid="alarm-filter" (change)="onAlarmFilter($event)">
              <option value="all">all</option>
              <option value="open">open</option>
              <option value="in-progress">in-progress</option>
              <option value="correlated">correlated</option>
              <option value="cleared">cleared</option>
            </select>
          </label>

          @for (g of store.correlationGroups(); track g.incidentId) {
            <div class="corr-group" data-testid="corr-group">
              <div class="corr-head">
                <a class="corr-inc" [routerLink]="['/incidents', g.incidentId]">{{ g.incidentId }}</a>
                <span class="corr-count">root cause + {{ g.children.length }} {{ g.children.length === 1 ? 'child' : 'children' }}</span>
                @if (g.groupRaisedAt) {
                  <span class="ts" data-testid="group-raised-at" [title]="g.groupRaisedAt | date: 'medium'">
                    {{ rel(g.groupRaisedAt) }}
                  </span>
                }
              </div>

              <!-- Root-cause alarm (highlighted) or graceful placeholder -->
              @if (g.rootCause; as rc) {
                <div class="alarm rca" data-testid="lifecycle-row" data-role="root-cause">
                  <span class="badge rca-badge" aria-label="Root cause alarm">&#9733; Root cause</span>
                  <span class="alarm-type">{{ label(rc) }}</span>
                  <span class="alarm-mo" [title]="rc.managedObjectId">{{ rc.managedObjectId }}</span>
                  <span class="badge state" [class]="stateClass(rc.lifecycleState)" data-testid="lifecycle-state">{{ rc.lifecycleState }}</span>
                  <span class="ts" data-testid="alarm-raised-at" [title]="rc.raisedAt ? (rc.raisedAt | date: 'medium') : ''">{{ rel(rc.raisedAt) || '—' }}</span>
                  <a class="alarm-id" [routerLink]="['/incidents', g.incidentId]">{{ rc.alarmId }}</a>
                </div>
              } @else {
                <div class="alarm rca rca-missing" data-testid="lifecycle-row" data-role="root-cause">
                  <span class="badge rca-badge" aria-label="Root cause alarm">&#9733; Root cause</span>
                  <span class="alarm-type muted">
                    @if (g.rootCauseAlarmType) {
                      {{ alarmTypeLabel(g.rootCauseAlarmType) }} — not yet resolved
                    } @else {
                      not yet resolved
                    }
                  </span>
                  <a class="alarm-id" [routerLink]="['/incidents', g.incidentId]">{{ g.incidentId }}</a>
                </div>
              }

              <!-- Child alarms (nested, subordinate) -->
              @for (c of g.children; track c.alarmId) {
                <div class="alarm child" data-testid="lifecycle-row" data-role="child">
                  <span class="tree" aria-hidden="true">&#9492;&#9472;</span>
                  <span class="alarm-type">{{ label(c) }}</span>
                  <span class="alarm-mo" [title]="c.managedObjectId">{{ c.managedObjectId }}</span>
                  <span class="badge state" [class]="stateClass(c.lifecycleState)" data-testid="lifecycle-state">{{ c.lifecycleState }}</span>
                  <span class="ts" data-testid="alarm-raised-at" [title]="c.raisedAt ? (c.raisedAt | date: 'medium') : ''">{{ rel(c.raisedAt) || '—' }}</span>
                  <a class="alarm-id" [routerLink]="['/incidents', g.incidentId]">{{ c.alarmId }}</a>
                </div>
              }
            </div>
          }

          <!-- Uncorrelated alarms -->
          @if (store.uncorrelatedAlarms().length) {
            <div class="corr-group uncorr" data-testid="uncorrelated-group">
              <div class="corr-head">
                <span class="corr-inc">Uncorrelated alarms</span>
                <span class="corr-count">{{ store.uncorrelatedAlarms().length }} not tied to an incident</span>
              </div>
              @for (u of store.uncorrelatedAlarms(); track u.alarmId) {
                <div class="alarm uncorr-row" data-testid="lifecycle-row" data-role="none">
                  <span class="alarm-type">{{ label(u) }}</span>
                  <span class="alarm-mo" [title]="u.managedObjectId">{{ u.managedObjectId }}</span>
                  <span class="badge state" [class]="stateClass(u.lifecycleState)" data-testid="lifecycle-state">{{ u.lifecycleState }}</span>
                  <span class="ts" data-testid="alarm-raised-at" [title]="u.raisedAt ? (u.raisedAt | date: 'medium') : ''">{{ rel(u.raisedAt) || '—' }}</span>
                  <span class="alarm-id">{{ u.alarmId }}</span>
                </div>
              }
            </div>
          }

          @if (!store.correlationGroups().length && !store.uncorrelatedAlarms().length) {
            <p class="empty-state">No alarms.</p>
          }
        </section>
      }
      @case ('noise') {
        <section class="card" aria-labelledby="noise-h">
          <h2 id="noise-h">Noise-filter run-stats</h2>
          <p><a routerLink="/chatter">Review &amp; promote observed chatter →</a></p>
          <label>
            filter trailId
            <input type="text" data-testid="trail-filter" (change)="onTrailFilter($event)" />
          </label>
          <table>
            <caption class="visually-hidden">Noise-filter per-run stats</caption>
            <thead>
              <tr><th scope="col">Run</th><th scope="col">Trail</th><th scope="col">In</th><th scope="col">Clusters</th><th scope="col">Kept</th><th scope="col">Dropped</th><th scope="col">Noise ratio</th><th scope="col">Storm reduction</th></tr>
            </thead>
            <tbody>
              @for (r of store.runStats(); track r.runId) {
                <tr data-testid="run-row">
                  <td>{{ r.runId }}</td>
                  <td>{{ r.trailId }}</td>
                  <td data-testid="run-alarmsIn">{{ r.alarmsIn }}</td>
                  <td>{{ r.clustersFormed }}</td>
                  <td>{{ r.alarmsKept }}</td>
                  <td>{{ r.alarmsDropped }}</td>
                  <td>{{ r.noiseRatio | number: '1.2-2' }}</td>
                  <td data-testid="run-storm">
                    @if (store.stormReduction(r) !== null) {
                      {{ store.stormReduction(r)! | number: '1.1-1' }} : 1
                    } @else {
                      N/A
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (!store.runStats().length) {
            <p class="empty-state">No run-stats yet.</p>
          }
        </section>
      }
    }
  `,
  styles: [
    `
      .kpis {
        display: flex;
        gap: 1.5rem;
        flex-wrap: wrap;
        margin-bottom: 1rem;
      }
      .tabs {
        display: flex;
        gap: 0.4rem;
        margin-bottom: 0.6rem;
      }
      .tabs button {
        background: var(--surface-2);
        color: var(--text-muted);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.4rem 0.8rem;
      }
      .tabs button.active {
        background: var(--accent-strong);
        color: #fff;
      }
      .list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      label {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
        margin-bottom: 0.6rem;
      }
      select,
      input[type='text'] {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .hint {
        color: var(--text-muted);
        margin: 0 0 0.6rem;
        font-size: 0.9rem;
      }
      .corr-group {
        border: 1px solid var(--border);
        border-radius: 8px;
        margin-bottom: 0.9rem;
        overflow: hidden;
        background: var(--surface);
      }
      .corr-head {
        display: flex;
        align-items: baseline;
        gap: 0.6rem;
        flex-wrap: wrap;
        padding: 0.5rem 0.75rem;
        background: var(--surface-2);
        border-bottom: 1px solid var(--border);
      }
      .corr-inc {
        font-weight: 700;
      }
      .corr-count {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .alarm {
        display: grid;
        grid-template-columns: auto 1fr auto auto auto auto;
        align-items: center;
        gap: 0.6rem;
        padding: 0.45rem 0.75rem;
        border-bottom: 1px solid var(--border);
      }
      .alarm:last-child {
        border-bottom: 0;
      }
      /* RCA highlight: accent left border + tinted background + bold type. */
      .alarm.rca {
        border-left: 4px solid var(--accent);
        background: color-mix(in srgb, var(--accent) 12%, transparent);
      }
      .alarm.rca .alarm-type {
        font-weight: 700;
      }
      .rca-badge {
        background: var(--accent-strong);
        color: var(--on-accent);
      }
      /* Children: indented + muted + connecting tree glyph. */
      .alarm.child {
        padding-left: 1.75rem;
        color: var(--text-muted);
      }
      .alarm.child .tree {
        color: var(--text-muted);
        font-family: monospace;
      }
      .rca-missing .alarm-type {
        font-style: italic;
      }
      .alarm-type {
        min-width: 0;
      }
      .alarm-mo {
        color: var(--text-muted);
        font-size: 0.85rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .alarm-id {
        font-family: monospace;
        font-size: 0.82rem;
      }
      .badge.state {
        text-transform: lowercase;
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
      .uncorr .corr-inc {
        color: var(--text-muted);
      }
      /* Relative timestamps: muted + monospace-ish, hover title shows the absolute time. */
      .ts {
        color: var(--text-muted);
        font-size: 0.8rem;
        white-space: nowrap;
        cursor: default;
      }
      .list .ts {
        margin-left: 0.4rem;
      }
      .corr-head .ts {
        margin-left: auto;
      }
      .muted {
        color: var(--text-muted);
      }
    `,
  ],
})
export class StatsComponent implements OnInit {
  readonly store = inject(StatsStore);
  readonly nav = inject(NavigationService);
  readonly tab = signal<Tab>('incidents');

  ngOnInit(): void {
    this.store.loadStats();
    this.store.loadIncidents();
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'alarms' && !this.store.alarms().length) {
      this.store.loadAlarms();
    }
    if (tab === 'noise' && !this.store.runStats().length) {
      this.store.loadRunStats();
    }
  }

  /** Expose the shared readable-label helper to the template (fallback labels). */
  readonly alarmTypeLabel = alarmTypeLabel;

  /** Readable alarm-type label, preferring `alarmType` then falling back to `eventType`. */
  label(a: AlarmSummary): string {
    return alarmTypeLabel(a.alarmType ?? a.eventType);
  }

  /** Tone class for the lifecycle-state badge. */
  stateClass(state: LifecycleState): string {
    return `state-${state}`;
  }

  /** Relative "… ago" form of an ISO-8601 timestamp for the timestamp cells (shared helper). */
  rel(iso: string | null | undefined): string {
    return relativeTime(iso);
  }

  onAlarmFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as LifecycleState | 'all';
    this.store.setAlarmFilter(value);
  }

  onTrailFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.store.loadRunStats(value || undefined);
  }
}
