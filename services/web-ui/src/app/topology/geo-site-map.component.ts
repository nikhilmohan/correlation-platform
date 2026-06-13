import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TopologyStore } from './topology.store';
import { NavigationService } from '../core/navigation.service';
import { ErrorBannerService } from '../core/error-banner.service';

/**
 * Geo-site map (spec task 6, AC 26). The entry view of the topology module. Each Site returned
 * by the Topology site query API is rendered as an accessible marker (a labelled button) on the
 * geo map. The MapLibre GL canvas is a progressive enhancement layered over the same data; the
 * accessible site list is the source of truth for keyboard/screen-reader users and for tests.
 * Supports the `?trailId=` deep link (AC 24) which is carried through to the site graph.
 */
@Component({
  selector: 'app-geo-site-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Topology &amp; trails — sites</h1>
    @if (errors.forService('Topology Service'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    <div
      class="geo-map"
      role="application"
      aria-label="Geographic map of network sites. Each site is selectable below."
    ></div>

    @if (store.sitesLoading()) {
      <p aria-busy="true">Loading sites…</p>
    } @else if (store.sites().length) {
      <ul class="site-markers" aria-label="Network sites">
        @for (site of store.sites(); track site.siteId) {
          <li>
            <button
              type="button"
              class="card site-marker"
              data-testid="site-marker"
              (click)="select(site.siteId)"
              [attr.aria-label]="'Site ' + site.name + ' in ' + site.region + '. Open device graph.'"
            >
              <strong>{{ site.name }}</strong>
              <span class="muted">{{ site.region }} · {{ site.latitude }}, {{ site.longitude }}</span>
            </button>
          </li>
        }
      </ul>
    } @else {
      <p class="empty-state">No sites returned.</p>
    }
  `,
  styles: [
    `
      .geo-map {
        height: 180px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: linear-gradient(135deg, #1e293b, #0f172a);
        margin-bottom: 1rem;
      }
      .site-markers {
        list-style: none;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 0.6rem;
        padding: 0;
        margin: 0;
      }
      .site-marker {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        text-align: left;
        color: var(--text);
        cursor: pointer;
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
    `,
  ],
})
export class GeoSiteMapComponent implements OnInit {
  readonly store = inject(TopologyStore);
  private readonly nav = inject(NavigationService);
  private readonly route = inject(ActivatedRoute);
  readonly errors = inject(ErrorBannerService);

  ngOnInit(): void {
    this.store.loadSites();
    const trailId = this.route.snapshot.queryParamMap.get('trailId');
    if (trailId) {
      this.store.activateTrail(trailId);
    }
  }

  select(siteId: string): void {
    this.nav.toSiteGraph(siteId);
  }
}
