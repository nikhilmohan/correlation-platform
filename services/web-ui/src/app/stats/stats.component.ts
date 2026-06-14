import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { StatsStore } from './stats.store';
import { NavigationService } from '../core/navigation.service';
import { LifecycleState } from '../api/models';

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
  imports: [DecimalPipe, PercentPipe, RouterLink],
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
          @if (store.incidents().length) {
            <ul class="list">
              @for (inc of store.incidents(); track inc.incidentId) {
                <li data-testid="stats-incident">
                  <a [routerLink]="['/incidents', inc.incidentId]">{{ inc.incidentId }}</a>
                  — root {{ inc.rootCauseAlarmType ?? inc.rootCauseAlarmId }}
                  · children: {{ inc.childAlarmIds.join(', ') || 'none' }}
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
          <table>
            <caption class="visually-hidden">Alarm lifecycle states</caption>
            <thead>
              <tr><th scope="col">Alarm</th><th scope="col">State</th><th scope="col">Role</th><th scope="col">Incident</th></tr>
            </thead>
            <tbody>
              @for (a of store.visibleAlarms(); track a.alarmId) {
                <tr data-testid="lifecycle-row">
                  <td>{{ a.alarmId }}</td>
                  <td data-testid="lifecycle-state">{{ a.lifecycleState }}</td>
                  <td>{{ a.role }}</td>
                  <td>
                    @if (a.incidentId) {
                      <a [routerLink]="['/incidents', a.incidentId]">{{ a.incidentId }}</a>
                    } @else {
                      —
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (!store.visibleAlarms().length) {
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

  onAlarmFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as LifecycleState | 'all';
    this.store.setAlarmFilter(value);
  }

  onTrailFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.store.loadRunStats(value || undefined);
  }
}
