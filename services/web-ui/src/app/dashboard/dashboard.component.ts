import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { DashboardStore } from './dashboard.store';
import { NavigationService } from '../core/navigation.service';
import { GeoSiteMapComponent } from '../topology/geo-site-map.component';

/**
 * Landing dashboard (default route). Shows the fleet KPI widgets across the top, then embeds the
 * FULL topology & trails view (the same `GeoSiteMapComponent` used by the `/topology` route) below
 * them — real basemap, site pins + native clustering, layer/trail features and site drill-in, all
 * on the dashboard. The heading is suppressed on the embed (`[showHeading]="false"`); the dashboard
 * supplies its own section header, and the map is given more height. The former "Recent incidents"
 * and "Quick links" cards were removed in favour of the embedded map.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DashboardStore],
  imports: [DecimalPipe, PercentPipe, GeoSiteMapComponent],
  template: `
    <div class="page-head">
      <h1>Platform overview</h1>
      <button class="btn btn-secondary" type="button" (click)="store.load()">Refresh</button>
    </div>

    <section class="kpis" aria-label="Key performance indicators">
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-incidents">
        <span class="kpi-icon" aria-hidden="true">◆</span>
        <span class="kpi-label">Live incidents</span>
        <span class="kpi-value">{{ store.incidentCount() }}</span>
      </button>
      <button class="card kpi" type="button" (click)="nav.toPatterns()" data-testid="kpi-patterns">
        <span class="kpi-icon" aria-hidden="true">❖</span>
        <span class="kpi-label">Active patterns</span>
        <span class="kpi-value">{{ store.activePatternCount() }}</span>
      </button>
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-reduction">
        <span class="kpi-icon" aria-hidden="true">▼</span>
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
        <span class="kpi-icon" aria-hidden="true">∑</span>
        <span class="kpi-label">Alarms processed</span>
        <span class="kpi-value">{{ store.stats()?.totalAlarmsProcessed ?? 0 }}</span>
      </div>
      <button class="card kpi" type="button" (click)="nav.toStats()" data-testid="kpi-rca">
        <span class="kpi-icon" aria-hidden="true">◎</span>
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
        <span class="kpi-icon" aria-hidden="true">⇄</span>
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

    <section class="topology-embed" aria-labelledby="dash-topo-h" data-testid="dashboard-topology">
      <h2 id="dash-topo-h">Network topology &amp; trails</h2>
      <app-geo-site-map [showHeading]="false" />
    </section>
  `,
  styles: [
    `
      .page-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin: 0.25rem 0 1.1rem;
      }
      .page-head h1 {
        margin: 0;
      }
      .kpis {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 0.9rem;
      }
      .kpi {
        position: relative;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        text-align: left;
        color: var(--text);
        overflow: hidden;
        transition:
          border-color 0.15s ease,
          box-shadow 0.15s ease,
          transform 0.15s ease;
      }
      button.kpi {
        cursor: pointer;
      }
      button.kpi:hover {
        border-color: var(--accent);
        box-shadow: var(--shadow-md);
        transform: translateY(-1px);
      }
      .kpi-icon {
        position: absolute;
        top: 0.6rem;
        right: 0.75rem;
        font-size: 1.15rem;
        color: var(--accent);
        opacity: 0.55;
        line-height: 1;
      }
      .kpi-label {
        color: var(--text-muted);
        font-size: 0.8rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        font-weight: 600;
      }
      .kpi-value {
        font-size: 1.75rem;
        font-weight: 700;
        line-height: 1.15;
      }
      .kpi-value small {
        font-size: 0.7rem;
        font-weight: 500;
        color: var(--text-muted);
      }
      .topology-embed {
        margin-top: 1.6rem;
      }
      .topology-embed > h2 {
        margin: 0 0 0.75rem;
        font-size: 1.2rem;
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
