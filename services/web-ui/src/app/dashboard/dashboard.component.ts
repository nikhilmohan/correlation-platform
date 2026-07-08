import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { PercentPipe } from '@angular/common';
import { DashboardStore } from './dashboard.store';
import { NavigationService } from '../core/navigation.service';
import { GeoSiteMapComponent } from '../topology/geo-site-map.component';
import { SiteGraphComponent } from '../topology/site-graph.component';
import { IngestionButtonComponent } from './ingestion-button.component';
import { ResetButtonComponent } from './reset-button.component';

/**
 * Landing dashboard (default route). Shows the fleet KPI widgets across the top, then embeds the
 * FULL topology & trails view below them — real basemap, site pins + native clustering, layer/trail
 * features and IN-PLACE site drill-in, all on the dashboard (there is no separate `/topology` page).
 *
 * The topology panel swaps between two states driven by `selectedSiteId`:
 *   - null  → the geo-site MAP (`<app-geo-site-map>`). Clicking a site emits its id via the map's
 *             `(siteSelected)` output, which the dashboard captures into selectedSiteId.
 *   - set   → the in-place SITE GRAPH (`<app-site-graph [siteId]=…>`) filling the same panel, with a
 *             Close button (data-testid="site-graph-close") that clears selectedSiteId → back to map.
 *
 * The map heading is suppressed on the embed (`[showHeading]="false"`); the dashboard supplies its
 * own section header. The former "Recent incidents" and "Quick links" cards were removed.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DashboardStore],
  imports: [
    PercentPipe,
    GeoSiteMapComponent,
    SiteGraphComponent,
    IngestionButtonComponent,
    ResetButtonComponent,
  ],
  template: `
    <div class="page-head">
      <h1>Platform overview</h1>
      <div class="page-actions">
        <app-ingestion-button />
        <app-reset-button />
        <button class="btn btn-secondary" type="button" (click)="store.load()">Refresh</button>
      </div>
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
      <div class="card kpi" data-testid="kpi-processed">
        <span class="kpi-icon" aria-hidden="true">∑</span>
        <span class="kpi-label">Alarms processed</span>
        <span class="kpi-value">{{ store.stats()?.totalAlarmsProcessed ?? 0 }}</span>
      </div>
      <button
        class="card kpi"
        type="button"
        (click)="nav.toStats()"
        data-testid="kpi-rca"
        aria-label="RCA accuracy — share of incidents whose tagged root-cause alarm exactly matches a simulator ground-truth root-cause alarm; N/A when no ground truth is available"
        [title]="RCA_HELP"
      >
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
      <div class="topo-head">
        <!-- When a site is selected the embedded SiteGraphComponent renders its own "Site graph — …"
             heading; keep THIS section heading distinct ("Site: …") so there is a single
             "Site graph" heading on the page (avoids a duplicate-heading strict-mode collision). -->
        <h2 id="dash-topo-h">
          @if (selectedSiteId(); as sid) {
            Site: {{ sid }}
          } @else {
            Network topology &amp; trails
          }
        </h2>
      </div>

      <!-- SHARED PANEL: the map and the in-place site graph render into the SAME fixed-size box, so
           swapping between them causes ZERO layout shift — the site view directly overlaps where the
           map was. Both children fill 100% of this panel (their own vh defaults only apply when the
           components are used standalone). -->
      <div class="topology-panel" [class.is-graph]="!!selectedSiteId()">
        @if (selectedSiteId(); as sid) {
          <!-- IN-PLACE site graph: fills the same panel as the map. Its own "← Back to map" button
               (data-testid="site-graph-close") emits (closed) → back to the map. -->
          <app-site-graph [siteId]="sid" [embedded]="true" (closed)="closeSite()" />
        } @else {
          <!-- Geo-site MAP. A site click emits its id → we swap to the site graph in-place. -->
          <app-geo-site-map [showHeading]="false" [embedded]="true" (siteSelected)="openSite($event)" />
        }
      </div>
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
      .page-actions {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
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
      .topo-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin: 0 0 0.75rem;
      }
      .topo-head h2 {
        margin: 0;
        font-size: 1.2rem;
      }
      /* SHARED topology panel: ONE fixed box hosting either the map or the site graph, so switching
         between them never shifts the page vertically. Both embedded children fill 100% of this box. */
      .topology-panel {
        position: relative;
        height: min(64vh, 720px);
        min-height: 480px;
        /* A flex column so the single child (map OR site-graph host) stretches to fill the fixed box
           height regardless of the child's own display. This is the parent that guarantees the
           embedded child has a resolved, non-zero height before Cytoscape / MapLibre mount + fit. */
        display: flex;
        flex-direction: column;
      }
      /* Do NOT set the display property here: the embedded child's own :host(.embedded-host) rule
         sets display:flex to make it a flex COLUMN (compact header + graph/map filling the rest). A
         display:block on this parent-child selector would win on specificity (parent .class > child
         :host) and collapse the child's flex chain -> the Cytoscape canvas gets zero height and the
         graph renders empty (the regression). We only stretch the child to the panel height via flex
         + min-height:0. */
      .topology-panel > app-geo-site-map,
      .topology-panel > app-site-graph {
        flex: 1 1 auto;
        min-height: 0;
        height: 100%;
      }
    `,
  ],
})
export class DashboardComponent implements OnInit {
  readonly store = inject(DashboardStore);
  readonly nav = inject(NavigationService);

  /** Tooltip copy for the RCA-accuracy KPI card (identical wording to the Alarms header card). */
  readonly RCA_HELP =
    'Root-cause accuracy: the share of incidents whose tagged root-cause alarm exactly matches a ' +
    'simulator ground-truth root-cause alarm (a direct root-cause alarm-id match), measured over all ' +
    'incidents. N/A when no ground truth is available.';

  /** null → show the geo-site map; a siteId → show the in-place site graph for that site. */
  readonly selectedSiteId = signal<string | null>(null);

  /** Drill into a site IN-PLACE (map → site graph) — wired from the map's (siteSelected) output. */
  openSite(siteId: string): void {
    this.selectedSiteId.set(siteId);
  }

  /** Close the in-place site graph and return to the map (Close button / site-graph `closed`). */
  closeSite(): void {
    this.selectedSiteId.set(null);
  }

  ngOnInit(): void {
    this.store.load();
  }
}
