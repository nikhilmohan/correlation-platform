import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, forkJoin, of } from 'rxjs';
import { TopologyClient } from '../api/topology.client';
import { TrailBuilderClient } from '../api/trail-builder.client';
import { ApiConfigService } from '../core/api-config.service';
import {
  EdgeDto,
  LogicalLayer,
  NeighborsDto,
  NodeDto,
  SiteDto,
  TrailDetail,
  TrailSummary,
} from '../api/models';
import { ALL_LAYERS, layerForEdge, layerForObjectType } from './layer-mapper';

export interface DerivedNode extends NodeDto {
  derivedLayer: LogicalLayer;
}
export interface DerivedEdge extends EdgeDto {
  derivedLayer: LogicalLayer;
}

/** Documented DEFAULT cap on the number of distinct device nodes the accumulating explorer graph
 *  will hold (OQ-12). The EFFECTIVE cap is resolved from env config (`TOPOLOGY_NODE_CAP`, same
 *  default) via `ApiConfigService` so it is operator-configurable per deployment (spec: a
 *  configurable cap). All-or-nothing semantics (AC 57): if an expansion's NEW deduped nodes would
 *  push the total OVER the cap, the WHOLE expansion is rejected (no partial add) and `capReached`
 *  flips true so the UI disables further expansion. Edges between already-present nodes still merge
 *  even at the cap. Keeps the graph legible and Cytoscape responsive. */
export const NODE_CAP = 250;

/**
 * Shared topology + trails state (signals). Drives the geo map, site graph, layer toggles,
 * attribute panel, and trail overlays/highlights (spec tasks 6-9, AC 26-32).
 *
 * EXPLORER MODEL: the site graph is an ACCUMULATING graph held in `nodeMap`/`edgeMap`. Rooting at a
 * site seeds it from objects-at-site; the operator then grows it explicitly via expandNode()
 * (pull a node's neighbours, cross-site/cross-domain on opt-in) or selectTrail() (explode the
 * topology to include a trail's full, possibly cross-site, member path). Nothing auto-grows.
 */
@Injectable({ providedIn: 'root' })
export class TopologyStore {
  private readonly topo = inject(TopologyClient);
  private readonly trailBuilder = inject(TrailBuilderClient);
  private readonly apiConfig = inject(ApiConfigService);

  /** Effective node cap (env-config `TOPOLOGY_NODE_CAP`, default {@link NODE_CAP}). */
  get nodeCap(): number {
    return this.apiConfig.topologyNodeCap;
  }

  readonly sites = signal<SiteDto[]>([]);
  readonly sitesLoading = signal<boolean>(false);
  readonly selectedSiteId = signal<string | null>(null);
  readonly graphLoading = signal<boolean>(false);

  /** Accumulating explorer graph, keyed by id so merges dedupe. Single writes per merge keep the
   *  structureKey (and thus the cytoscape relayout) firing exactly once per logical change. */
  readonly nodeMap = signal<ReadonlyMap<string, NodeDto>>(new Map());
  readonly edgeMap = signal<ReadonlyMap<string, EdgeDto>>(new Map());
  /** True once any node is present (replaces the old `objects()` truthiness gate). */
  readonly hasGraph = computed(() => this.nodeMap().size > 0);
  /** True once expansion hit NODE_CAP — the UI disables further expand controls. */
  readonly capReached = signal<boolean>(false);

  readonly visibleLayers = signal<ReadonlySet<LogicalLayer>>(new Set(ALL_LAYERS));
  readonly selectedObjectId = signal<string | null>(null);
  readonly selectedEdgeId = signal<string | null>(null);
  /** Ids of nodes the operator has already expanded (so the +expand affordance can reflect state). */
  readonly expandedNodeIds = signal<ReadonlySet<string>>(new Set());

  readonly trails = signal<TrailSummary[]>([]);
  /** Trails the SELECTED DEVICE belongs to (AC 32) — device-select list highlight. */
  readonly highlightedTrailIds = signal<ReadonlySet<string>>(new Set());
  readonly activeTrailId = signal<string | null>(null);

  /** The explicitly-selected trail being explored (clicked in the trail-cluster list). */
  readonly selectedTrailId = signal<string | null>(null);
  /** Full detail (members) of the selected trail — sourced from getTrail. */
  readonly selectedTrailDetail = signal<TrailDetail | null>(null);
  /** managedObjectIds of the selected trail's members (the FULL member path, not just one node). */
  readonly trailMemberIds = signal<ReadonlySet<string>>(new Set());

  /** The siteId of the in-flight objects-at-site request; stale responses are dropped (guards
   *  against a slower prior request clobbering the current site / clearing loading wrongly). */
  private pendingObjectsSiteId: string | null = null;

  readonly derivedNodes = computed<DerivedNode[]>(() => {
    // A node whose id is a known siteId is a boundary CONTAINER, not a device — it renders as a
    // compound box (from nodeSiteMap), never as a leaf node, so exclude it from the device set.
    const siteIds = new Set(this.sites().map((s) => s.siteId));
    return Array.from(this.nodeMap().values())
      .filter((n) => !siteIds.has(n.managedObjectId))
      .map((n) => ({ ...n, derivedLayer: layerForObjectType(n.objectType) }));
  });
  readonly derivedEdges = computed<DerivedEdge[]>(() =>
    Array.from(this.edgeMap().values()).map((e) => ({
      ...e,
      // Edge layer is driven by the typed `relation` (every Topology §5 relation resolves to one of
      // the five toggleable layers), falling back to the endpoint objectType only for an unknown
      // relation. This is what makes AC 28's all-off → 0 edges invariant hold (#263): no rendered
      // edge is left in the un-toggleable `other` layer.
      derivedLayer: layerForEdge(e),
    })),
  );

  /** Nodes/edges currently visible given the layer toggles (AC 28). Nodes always render; edges
   *  hidden when their derived layer is toggled off (all-off => only nodes). */
  readonly visibleEdges = computed<DerivedEdge[]>(() => {
    const layers = this.visibleLayers();
    return this.derivedEdges().filter((e) => layers.has(e.derivedLayer));
  });

  readonly selectedNode = computed<DerivedNode | null>(() => {
    const id = this.selectedObjectId();
    return id ? (this.derivedNodes().find((n) => n.managedObjectId === id) ?? null) : null;
  });
  readonly selectedEdge = computed<DerivedEdge | null>(() => {
    const id = this.selectedEdgeId();
    return id ? (this.derivedEdges().find((e) => e.edgeId === id) ?? null) : null;
  });

  /**
   * Map of device managedObjectId → its siteId, derived from LOCATED_AT edges in the accumulating
   * graph. The SITE endpoint of a LOCATED_AT edge is whichever endpoint is a known siteId; the
   * other endpoint is the device. Drives the compound site-boundary boxes in the graph. A site that
   * has itself been seeded as a node also maps to itself (so its parent box renders).
   */
  readonly nodeSiteMap = computed<ReadonlyMap<string, string>>(() => {
    const siteIds = new Set(this.sites().map((s) => s.siteId));
    const map = new Map<string, string>();
    for (const e of this.edgeMap().values()) {
      if (e.relation !== 'LOCATED_AT') {
        continue;
      }
      // The site endpoint is the one whose id is a known siteId; the other is the device.
      if (siteIds.has(e.to)) {
        map.set(e.from, e.to);
      } else if (siteIds.has(e.from)) {
        map.set(e.to, e.from);
      }
    }
    return map;
  });

  /** Friendly name for a siteId (falls back to the id). */
  siteName(siteId: string): string {
    return this.sites().find((s) => s.siteId === siteId)?.name ?? siteId;
  }

  /** Distinct sites currently represented in the graph (drives the site legend + bridge). */
  readonly distinctSiteIds = computed<string[]>(() => {
    const ids = new Set(this.nodeSiteMap().values());
    return Array.from(ids);
  });

  loadSites(): void {
    this.sitesLoading.set(true);
    this.topo
      .listSites()
      .pipe(catchError(() => of({ domain: '', snapshotId: '', count: 0, sites: [] })))
      .subscribe((res) => {
        this.sites.set(res.sites);
        this.sitesLoading.set(false);
      });
  }

  /**
   * ROOT the explorer graph at a site (the entry into the site view). Kept named `selectSite` so the
   * geo-map / site-graph call sites don't churn. Clears the accumulating graph + all selection and
   * trail/expansion state, then seeds it from objects-at-site for the site. A stale in-flight
   * response for a previously-selected site can never clobber the current one (pendingObjectsSiteId
   * guard); the loading flag clears deterministically on BOTH success and error.
   */
  selectSite(siteId: string): void {
    this.selectedSiteId.set(siteId);
    this.graphLoading.set(true);
    this.nodeMap.set(new Map());
    this.edgeMap.set(new Map());
    this.selectedObjectId.set(null);
    this.selectedEdgeId.set(null);
    this.expandedNodeIds.set(new Set());
    this.capReached.set(false);
    this.highlightedTrailIds.set(new Set());
    this.clearTrail();
    this.pendingObjectsSiteId = siteId;
    this.topo
      .objectsAtSite(siteId)
      .pipe(catchError(() => of(null)))
      .subscribe((res) => {
        if (this.pendingObjectsSiteId !== siteId) {
          return; // a newer selectSite superseded this request — ignore the stale response.
        }
        if (res) {
          this.mergeGraph(res.nodes, res.edges);
        }
        this.graphLoading.set(false);
      });
    this.loadTrails();
  }

  /** Re-root the graph at the current site, discarding all expansions (the "Reset" affordance). */
  collapseToRoot(): void {
    const siteId = this.selectedSiteId();
    if (siteId) {
      this.selectSite(siteId);
    }
  }

  /**
   * EXPAND: pull a node's immediate neighbours into the accumulating graph (operator-driven).
   * crossDomain opt-in extends across the domain boundary. No-ops once NODE_CAP is reached.
   */
  expandNode(managedObjectId: string, opts: { relation?: string; crossDomain?: boolean } = {}): void {
    if (this.capReached()) {
      return;
    }
    this.topo
      .neighbors(managedObjectId, opts)
      .pipe(catchError(() => of(null)))
      .subscribe((res: NeighborsDto | null) => {
        if (!res) {
          return;
        }
        const merged = this.mergeGraph(
          res.neighbors.map((n) => n.node),
          res.neighbors.map((n) => n.via),
        );
        // Only mark the node expanded when the expansion was actually applied; an all-or-nothing
        // rejection at the cap (AC 57) leaves the graph — and the expanded set — unchanged.
        if (merged) {
          const next = new Set(this.expandedNodeIds());
          next.add(managedObjectId);
          this.expandedNodeIds.set(next);
        }
      });
  }

  loadTrails(): void {
    this.trailBuilder
      .listTrails()
      .pipe(catchError(() => of({ snapshotId: '', domain: '', count: 0, trails: [] })))
      .subscribe((res) => this.trails.set(res.trails));
  }

  toggleLayer(layer: LogicalLayer): void {
    const next = new Set(this.visibleLayers());
    if (next.has(layer)) {
      next.delete(layer);
    } else {
      next.add(layer);
    }
    this.visibleLayers.set(next);
  }

  setLayerVisible(layer: LogicalLayer, visible: boolean): void {
    const next = new Set(this.visibleLayers());
    if (visible) {
      next.add(layer);
    } else {
      next.delete(layer);
    }
    this.visibleLayers.set(next);
  }

  selectNode(managedObjectId: string): void {
    this.selectedObjectId.set(managedObjectId);
    this.selectedEdgeId.set(null);
    // Selecting a device clears any explicit trail exploration (device-anchored view).
    this.selectedTrailId.set(null);
    this.trailMemberIds.set(new Set());
    // Highlight all trails the device belongs to (AC 32).
    this.trailBuilder
      .getTrailsForObject(managedObjectId)
      .pipe(catchError(() => of({ managedObjectId, domain: '', trailIds: [] })))
      .subscribe((res) => this.highlightedTrailIds.set(new Set(res.trailIds)));
  }

  selectEdge(edgeId: string): void {
    this.selectedEdgeId.set(edgeId);
    this.selectedObjectId.set(null);
    this.highlightedTrailIds.set(new Set());
  }

  /**
   * Clear the current device/edge selection (closes the detail drawer). Keeps any explicit trail
   * exploration intact — only the object-anchored selection + its trail-highlight are cleared.
   */
  clearSelection(): void {
    this.selectedObjectId.set(null);
    this.selectedEdgeId.set(null);
    this.highlightedTrailIds.set(new Set());
  }

  /**
   * SELECT + EXPLODE a trail: highlight its full member path and pull any members not yet in the
   * graph (with their connecting edges) so the — possibly cross-site — path actually renders. The
   * member set is sourced from getTrail (the TrailSummary list carries no members).
   */
  selectTrail(trailId: string): void {
    this.selectedTrailId.set(trailId);
    this.selectedObjectId.set(null);
    this.selectedEdgeId.set(null);
    this.trailBuilder
      .getTrail(trailId)
      .pipe(catchError(() => of(null)))
      .subscribe((detail: TrailDetail | null) => {
        if (!detail) {
          return;
        }
        this.selectedTrailDetail.set(detail);
        const memberIds = detail.members.map((m) => m.managedObjectId);
        this.trailMemberIds.set(new Set(memberIds));
        // EXPLODE: pull every member not already present into the graph so the — possibly cross-site
        // — path actually renders. Each missing member is synthesized as a node from its TrailMember
        // (so the member itself is guaranteed present) AND its neighbours are fetched so its
        // connecting edges (and the link to the rest of the path) come in. forkJoin the union so the
        // merge runs once (one structureKey change → one relayout).
        const present = this.nodeMap();
        const missing = detail.members.filter((m) => !present.has(m.managedObjectId));
        if (missing.length === 0) {
          return;
        }
        const memberNodes: NodeDto[] = missing.map((m) => ({
          managedObjectId: m.managedObjectId,
          objectType: m.objectType,
          domain: detail.domain,
          snapshotId: detail.snapshotId,
          attributes: {},
        }));
        forkJoin(
          missing.map((m) => this.topo.neighbors(m.managedObjectId).pipe(catchError(() => of(null)))),
        ).subscribe((results) => {
          const nodes: NodeDto[] = [...memberNodes];
          const edges: EdgeDto[] = [];
          for (const r of results) {
            if (!r) {
              continue;
            }
            for (const n of r.neighbors) {
              nodes.push(n.node);
              edges.push(n.via);
            }
          }
          this.mergeGraph(nodes, edges);
        });
      });
  }

  /** Clear the explicit trail exploration (keeps the exploded nodes in the graph). */
  clearTrail(): void {
    this.selectedTrailId.set(null);
    this.selectedTrailDetail.set(null);
    this.trailMemberIds.set(new Set());
  }

  activateTrail(trailId: string): void {
    this.activeTrailId.set(trailId);
    this.highlightedTrailIds.set(new Set([trailId]));
  }

  /**
   * Merge nodes + edges into the accumulating graph, deduping by id, with an **ALL-OR-NOTHING**
   * node cap (AC 56, 57). The cap check is on the DEDUPED NEW node set (nodes not already present):
   *
   *   - **Fits** (`nodeMap.size + newNodes.length ≤ cap`) → additive merge of the new nodes + new
   *     edges (deduped by `edgeId`), ONE write each (single structureKey change → one relayout).
   *   - **Would overflow** → reject the WHOLE expansion: add **nothing** (no partial add — AC 57),
   *     set `capReached = true`. The visible cap notice + disabled expand controls then surface it.
   *   - **Zero new nodes** (re-expand of a fully-present node, or an edges-only merge between
   *     already-present nodes) → never overflows; the new edges still merge even at the cap (AC 56).
   *
   * @returns true when nodes/edges were merged, false when the whole expansion was rejected at cap.
   */
  private mergeGraph(nodes: readonly NodeDto[], edges: readonly EdgeDto[]): boolean {
    const current = this.nodeMap();
    const cap = this.nodeCap;

    // Deduped NEW node set: present-once, not already in the graph (first-seen wins).
    const newNodes = new Map<string, NodeDto>();
    for (const n of nodes) {
      if (!n || current.has(n.managedObjectId) || newNodes.has(n.managedObjectId)) {
        continue;
      }
      newNodes.set(n.managedObjectId, n);
    }

    // ALL-OR-NOTHING (AC 57): if the deduped NEW nodes would push the total over the cap, reject the
    // whole expansion — add NOTHING (not even edges) and flag capReached so the UI can react. An
    // expansion with zero new nodes can never overflow (covers AC 56 re-expand + edges-only merges).
    if (newNodes.size > 0 && current.size + newNodes.size > cap) {
      if (!this.capReached()) {
        this.capReached.set(true);
      }
      return false;
    }

    // Fits — additive merge. One write to each map keeps the structureKey changing exactly once.
    const nextNodes = new Map(current);
    for (const [id, n] of newNodes) {
      nextNodes.set(id, n);
    }
    const nextEdges = new Map(this.edgeMap());
    for (const e of edges) {
      if (!e || nextEdges.has(e.edgeId)) {
        continue;
      }
      nextEdges.set(e.edgeId, e);
    }
    this.nodeMap.set(nextNodes);
    this.edgeMap.set(nextEdges);
    return true;
  }
}
