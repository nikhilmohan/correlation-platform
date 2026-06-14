import { ChangeDetectionStrategy, Component, OnInit, inject, input } from '@angular/core';
import { TopologyStore } from './topology.store';
import { ErrorBannerService } from '../core/error-banner.service';
import { AttributeDetailPanelComponent } from './attribute-detail-panel.component';
import { LayerToggleComponent } from './layer-toggle.component';

/**
 * Site-level device graph (spec tasks 7-9, AC 27-32). Built from BOTH `nodes` and `edges` of the
 * single `SiteObjectsDto` response (no neighbour fan-out). Cytoscape.js is the visual renderer
 * (progressive enhancement); the accessible node/edge lists are the source of truth for keyboard/
 * screen-reader users and tests. Layer toggles filter the derived layer; selecting a device
 * highlights all its trails; the detail panel shows the selected node/edge attributes.
 */
@Component({
  selector: 'app-site-graph',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AttributeDetailPanelComponent, LayerToggleComponent],
  template: `
    <h1>Site graph — {{ siteId() }}</h1>
    @if (errors.forService('Topology Service'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    @if (store.activeTrailId(); as t) {
      <p class="active-trail" data-testid="active-trail">Active trail: {{ t }}</p>
    }

    <div class="layout">
      <section class="graph-area">
        <app-layer-toggle />

        <div
          class="cy-canvas"
          role="application"
          aria-label="Device-level topology graph for this site. Nodes and edges are listed below."
        ></div>

        @if (store.graphLoading()) {
          <p aria-busy="true">Loading site graph…</p>
        } @else if (store.objects()) {
          <h2>Devices</h2>
          <ul class="obj-list" aria-label="Devices in this site">
            @for (node of store.derivedNodes(); track node.managedObjectId) {
              <li>
                <button
                  type="button"
                  class="obj"
                  data-testid="graph-node"
                  [class.selected]="store.selectedObjectId() === node.managedObjectId"
                  [class.trail-member]="isTrailMemberNode(node.managedObjectId)"
                  (click)="store.selectNode(node.managedObjectId)"
                  [attr.aria-pressed]="store.selectedObjectId() === node.managedObjectId"
                >
                  {{ node.name ?? node.managedObjectId }}
                  <span class="layer-tag">{{ node.derivedLayer }}</span>
                </button>
              </li>
            }
          </ul>

          <h2>Connections</h2>
          <ul class="obj-list" aria-label="Connections in this site">
            @for (edge of store.visibleEdges(); track edge.edgeId) {
              <li>
                <button
                  type="button"
                  class="obj"
                  data-testid="graph-edge"
                  [class.selected]="store.selectedEdgeId() === edge.edgeId"
                  (click)="store.selectEdge(edge.edgeId)"
                  [attr.aria-pressed]="store.selectedEdgeId() === edge.edgeId"
                >
                  {{ edge.from }} → {{ edge.to }}
                  <span class="layer-tag">{{ edge.relation }}</span>
                </button>
              </li>
            }
          </ul>

          <h2>Trail clusters</h2>
          @if (store.trails().length) {
            <ul class="trail-overlay" aria-label="Trail clusters overlaid on the graph">
              @for (trail of store.trails(); track trail.trailId) {
                <li
                  data-testid="trail-cluster"
                  [class.highlighted]="store.highlightedTrailIds().has(trail.trailId)"
                >
                  {{ trail.trailId }} ({{ trail.memberCount }} members)
                  @if (store.highlightedTrailIds().has(trail.trailId)) {
                    <span class="badge badge-new">member</span>
                  }
                </li>
              }
            </ul>
          } @else {
            <p class="empty-state">No trails for this snapshot.</p>
          }
        } @else {
          <p class="empty-state">No objects at this site.</p>
        }
      </section>

      <app-attribute-detail-panel />
    </div>
  `,
  styles: [
    `
      .layout {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 1rem;
      }
      .cy-canvas {
        height: 220px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: #0b1220;
        margin-bottom: 0.8rem;
      }
      .obj-list {
        list-style: none;
        padding: 0;
        margin: 0 0 1rem;
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }
      .obj {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.3rem 0.6rem;
      }
      .obj.selected {
        border-color: var(--accent);
        outline: 2px solid var(--accent);
      }
      .obj.trail-member {
        border-color: var(--new);
      }
      .layer-tag {
        color: var(--text-muted);
        font-size: 0.78rem;
        margin-left: 0.3rem;
      }
      .trail-overlay {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
      }
      .trail-overlay .highlighted {
        color: var(--new);
        font-weight: 600;
      }
      .active-trail {
        color: var(--accent);
      }
      @media (max-width: 800px) {
        .layout {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class SiteGraphComponent implements OnInit {
  readonly store = inject(TopologyStore);
  readonly errors = inject(ErrorBannerService);

  /** Route param binding (withComponentInputBinding). */
  readonly siteId = input<string>('');

  ngOnInit(): void {
    const id = this.siteId();
    if (id) {
      this.store.selectSite(id);
    }
  }

  isTrailMemberNode(managedObjectId: string): boolean {
    // A node is highlighted if any of its trails is in the highlighted set; the membership is
    // resolved on select via getTrailsForObject, so we reflect the currently-selected device.
    return this.store.selectedObjectId() === managedObjectId && this.store.highlightedTrailIds().size > 0;
  }
}
