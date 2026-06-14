import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DashboardStore } from './dashboard.store';
import { NavigationService } from '../core/navigation.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DashboardStore],
  imports: [RouterLink, DecimalPipe, PercentPipe],
  template: `
    <h1>Platform overview</h1>
    <div class="toolbar">
      <button class="btn btn-secondary" type="button" (click)="store.load()">Refresh</button>
    </div>

    <section class="kpis" aria-label="Key performance indicators">
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-incidents">
        <span class="kpi-label">Live incidents</span>
        <span class="kpi-value">{{ store.incidentCount() }}</span>
      </button>
      <button class="card kpi" type="button" (click)="nav.toPatterns()" data-testid="kpi-patterns">
        <span class="kpi-label">Active patterns</span>
        <span class="kpi-value">{{ store.activePatternCount() }}</span>
      </button>
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-reduction">
        <span class="kpi-label">Alarm reduction</span>
        <span class="kpi-value">
          @if (store.alarmReductionRatio() !== null) {
            {{ store.alarmReductionRatio() | number: '1.1-1' }} : 1
          } @else {
            N/A
          }
        </span>
      </button>
      <div class="card kpi" data-testid="kpi-processed">
        <span class="kpi-label">Alarms processed</span>
        <span class="kpi-value">{{ store.stats()?.totalAlarmsProcessed ?? 0 }}</span>
      </div>
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-rca">
        <span class="kpi-label">RCA accuracy</span>
        <span class="kpi-value">
          @if (store.rcaAccuracy().value !== null) {
            {{ store.rcaAccuracy().value! | percent: '1.0-1' }}
            <small>({{ store.rcaAccuracy().source }})</small>
          } @else {
            N/A (no ground truth)
          }
        </span>
      </button>
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-autocorr">
        <span class="kpi-label">Auto-correlation</span>
        <span class="kpi-value">
          @if (store.autoCorrelationPct() !== null) {
            {{ store.autoCorrelationPct()! | percent: '1.1-1' }}
          } @else {
            N/A
          }
        </span>
      </button>
    </section>

    <div class="grid">
      <section class="card" aria-labelledby="recent-h">
        <h2 id="recent-h">Recent incidents</h2>
        @if (store.incidents().length) {
          <ul class="incident-list">
            @for (inc of store.incidents(); track inc.incidentId) {
              <li>
                <a [routerLink]="['/incidents', inc.incidentId]" data-testid="recent-incident">
                  {{ inc.incidentId }} — root {{ inc.rootCauseAlarmType ?? inc.rootCauseAlarmId }}
                </a>
              </li>
            }
          </ul>
        } @else {
          <p class="empty-state">No incidents yet.</p>
        }
      </section>

      <nav class="card" aria-labelledby="quick-h">
        <h2 id="quick-h">Quick links</h2>
        <ul class="quick-links">
          <li><a routerLink="/streaming">Streaming (live)</a></li>
          <li><a routerLink="/topology">Topology + trails</a></li>
          <li><a routerLink="/patterns">Pattern review</a></li>
          <li><a routerLink="/chatter">Chatter management</a></li>
          <li><a routerLink="/config">Config (Knowledge)</a></li>
          <li><a routerLink="/stats">Correlation stats</a></li>
        </ul>
      </nav>
    </div>
  `,
  styles: [
    `
      .toolbar {
        margin: 0.5rem 0 1rem;
      }
      .kpis {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: 0.8rem;
      }
      .kpi {
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
        text-align: left;
        color: var(--text);
      }
      button.kpi {
        cursor: pointer;
      }
      .kpi-label {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .kpi-value {
        font-size: 1.6rem;
        font-weight: 700;
      }
      .grid {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 1rem;
        margin-top: 1rem;
      }
      .incident-list,
      .quick-links {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      @media (max-width: 800px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class DashboardComponent implements OnInit {
  readonly store = inject(DashboardStore);
  readonly nav = inject(NavigationService);

  ngOnInit(): void {
    this.store.load();
  }
}
