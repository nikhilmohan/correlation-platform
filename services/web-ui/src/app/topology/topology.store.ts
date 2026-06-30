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
  /** Ids of nodes the operator has already expanded (so the +expand affordance can reflect state).
   *  Note: this holds the SOURCE node ids that were expanded, NOT the revealed neighbours. */
  readonly expandedNodeIds = signal<ReadonlySet<string>>(new Set());

  /**
   * CHANGE 2: the set of node ids that were NEWLY REVEALED by an external expandNode() (the off-site
   * neighbours pulled in, not the source node that was already present). This is the EXPAND-EXTERNAL
   * layer's added-id tracking — the precise inverse set that collapseExternal() removes, mirroring
   * trailExplodedNodeIds for the trail layer. Empty until the operator reveals an external link;
   * cleared on selectSite/collapseToRoot and on collapseExternal(). Drives the "Hide external links"
   * control's visibility (shown only while this is non-empty).
   */
  readonly externalRevealedNodeIds = signal<ReadonlySet<string>>(new Set());

  /**
   * CHANGE 1: in-site device ids that have at least one OFF-SITE neighbour (a hidden external link).
   * Computed once per site load (a neighbours probe per seeded in-site device); drives the amber
   * "extends externally" (↗) node cue. A node whose external neighbours have all been pulled into the
   * graph (so nothing external remains hidden) is dropped from this set, so the cue disappears once a
   * device is fully revealed. Detection approach (a): probe each seeded node's neighbours and flag it
   * if any neighbour is not in the in-site node set — the site /objects edges only reference in-site
   * objects, so dangling cross-site edges are not inferable from them (they live in the neighbours
   * endpoint). Probe cost: one /neighbors call per seeded in-site device, fired once at site load.
   */
  readonly externalLinkNodeIds = signal<ReadonlySet<string>>(new Set());

  /** The siteId for which externalLinkNodeIds was computed; stale probe responses are dropped. */
  private pendingExternalSiteId: string | null = null;
  /** Per-node OFF-SITE neighbour ids discovered by the probe (used to drop the cue once revealed). */
  private externalNeighboursByNode = new Map<string, Set<string>>();

  /** All trails in the snapshot (from listTrails) — the full set used to resolve site membership. */
  readonly allTrails = signal<TrailSummary[]>([]);

  /**
   * CHANGE 2a: the set of trailIds the CURRENT SITE participates in (union of getTrailsForObject
   * across the site's seeded device ids). `null` while not yet computed (then `trails` falls back to
   * the full set so the count is never momentarily empty); a concrete Set once the per-site probe
   * resolves. Cached per site (recomputed only on selectSite).
   */
  readonly siteTrailIds = signal<ReadonlySet<string> | null>(null);

  /**
   * CHANGE 2a: trails SCOPED TO THE CURRENT SITE — only those whose id is in siteTrailIds. This is
   * what the dropdown lists and the "Trails (N)" count + data-cy-trail-count bridge reflect. Until the
   * site probe resolves (siteTrailIds === null) it falls back to the full snapshot list.
   */
  readonly trails = computed<TrailSummary[]>(() => {
    const scope = this.siteTrailIds();
    const all = this.allTrails();
    if (scope === null) {
      return all;
    }
    return all.filter((t) => scope.has(t.trailId));
  });

  /** The siteId for which siteTrailIds was computed; stale probe responses are dropped. */
  private pendingSiteTrailsSiteId: string | null = null;

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

  /**
   * FOUR-VIEW STATE MODEL — the BASE snapshot of the pure in-site graph, taken right after
   * objectsAtSite seeds the graph (before ANY explode or external-expand). It is the stable anchor:
   * NOTHING (select / expand / explode / contract / clear) re-lays-out or re-fits the base — base
   * node positions stay fixed across every transition (the component's rebuildAndLayout locks
   * existing positions). The base id set drives quick "is this a base node" checks. Copies of the
   * seeded node/edge maps are kept so later mutation can never alias the base.
   *
   * The view model is composed of THREE INDEPENDENT, ADDITIVE layers on top of this base:
   *   1. DEFAULT SITE VIEW   = base only.
   *   2. EXPAND-EXTERNAL     = base + nodes revealed via expandNode. The SOURCE node ids that were
   *                            expanded are tracked in expandedNodeIds; the actual REVEALED neighbour
   *                            ids are tracked in externalRevealedNodeIds so collapseExternal() can
   *                            remove EXACTLY those (the precise inverse of the reveals), leaving the
   *                            base + trail layers untouched.
   *   3. TRAIL SELECTED      = base (UNCHANGED — no relayout, no add/remove) + magenta highlight of
   *                            the selected trail's in-site members.
   *   4. TRAIL EXPLODED      = TRAIL SELECTED + the trail's cross-site member nodes ADDED (tracked in
   *                            trailExplodedNodeIds, so contract removes EXACTLY those).
   * Layers 2 and 4 are independent: an external reveal is never torn down by trail explode/contract/
   * clear, and a trail explode/contract never removes an externally-revealed node.
   */
  private baseNodeMap: ReadonlyMap<string, NodeDto> = new Map();
  private baseEdgeMap: ReadonlyMap<string, EdgeDto> = new Map();
  private baseNodeIds: ReadonlySet<string> = new Set();

  /** TRAIL EXPLODED layer: the node ids THIS trail explosion added to the graph (cross-site members +
   *  their pulled-in neighbours that were not already present). Contract removes EXACTLY these ids (and
   *  their now-dangling edges), so the base and any independently-expanded external nodes are untouched.
   *  Cleared on contract / clear / a switch to a different trail. */
  private trailExplodedNodeIds: ReadonlySet<string> = new Set();

  /** True while the currently-selected trail has been EXPLODED to its full (cross-site) path. Drives
   *  the explode/contract TOGGLE pressed/label state; cleared on contract / clear / site change. */
  readonly explodeActive = signal<boolean>(false);

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
    // Invalidate the prior site's base snapshot BEFORE the pre-seed clearTrail() below, so its
    // restore no-ops (selectSite owns the reset here); the new base is snapshotted once the new
    // site's objects seed in.
    this.baseNodeMap = new Map();
    this.baseEdgeMap = new Map();
    this.baseNodeIds = new Set();
    this.trailExplodedNodeIds = new Set();
    this.explodeActive.set(false);
    this.nodeMap.set(new Map());
    this.edgeMap.set(new Map());
    this.selectedObjectId.set(null);
    this.selectedEdgeId.set(null);
    this.expandedNodeIds.set(new Set());
    this.externalRevealedNodeIds.set(new Set());
    this.capReached.set(false);
    this.highlightedTrailIds.set(new Set());
    this.externalLinkNodeIds.set(new Set());
    this.externalNeighboursByNode = new Map();
    this.pendingExternalSiteId = siteId;
    this.siteTrailIds.set(null);
    this.pendingSiteTrailsSiteId = siteId;
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
          // SNAPSHOT the pure in-site base graph (before any explode/expand) so trail-change and
          // clearTrail can restore to it (resettable full-path). Copies are taken so later mutation
          // of nodeMap/edgeMap can never alias the base.
          this.baseNodeMap = new Map(this.nodeMap());
          this.baseEdgeMap = new Map(this.edgeMap());
          this.baseNodeIds = new Set(this.baseNodeMap.keys());
        }
        // Clear loading FIRST (the graph is rendered from the merged objects); the external-link +
        // site-trail probes are async enrichments that must never block or fail the load.
        this.graphLoading.set(false);
        if (res) {
          this.probeExternalLinks(siteId, res.nodes);
          this.computeSiteTrails(siteId, res.nodes);
        }
      });
    this.loadTrails();
  }

  /**
   * CHANGE 1: probe each seeded in-site device's neighbours and flag the ones with ≥1 OFF-SITE
   * neighbour (a hidden external link). Sites are identified by their known siteId set, so a
   * neighbour that is itself a site CONTAINER is ignored; a neighbour device already in the in-site
   * set is in-site. One /neighbors call per seeded device, fired once at site load. The flagged ids
   * drive the amber ↗ cue; recomputeExternalCues() later drops a node once its external neighbours
   * are all present (fully revealed).
   */
  private probeExternalLinks(siteId: string, seeded: readonly NodeDto[]): void {
    // Defensive: a narrow test stub may omit neighbors(); the cue is an enrichment, never required.
    if (typeof this.topo.neighbors !== 'function' || seeded.length === 0) {
      return;
    }
    const inSite = new Set(seeded.map((n) => n.managedObjectId));
    const siteIds = new Set(this.sites().map((s) => s.siteId));
    forkJoin(
      seeded.map((n) =>
        this.topo.neighbors(n.managedObjectId).pipe(catchError(() => of(null))),
      ),
    ).subscribe((results) => {
      if (this.pendingExternalSiteId !== siteId) {
        return; // a newer selectSite superseded this probe — ignore the stale response.
      }
      const byNode = new Map<string, Set<string>>();
      results.forEach((r, i) => {
        if (!r) {
          return;
        }
        const nodeId = seeded[i].managedObjectId;
        const external = new Set<string>();
        for (const entry of r.neighbors) {
          const nid = entry.node.managedObjectId;
          // A site-container neighbour is structural placement, not an external link; skip it. A
          // neighbour outside the in-site device set is an off-site link.
          if (siteIds.has(nid) || inSite.has(nid)) {
            continue;
          }
          external.add(nid);
        }
        if (external.size > 0) {
          byNode.set(nodeId, external);
        }
      });
      this.externalNeighboursByNode = byNode;
      this.recomputeExternalCues();
    });
  }

  /** Recompute the amber-cue set: a node keeps the cue while ANY of its off-site neighbours is still
   *  hidden (not yet in the accumulating graph). Fully-revealed nodes drop the cue. */
  private recomputeExternalCues(): void {
    const present = this.nodeMap();
    const next = new Set<string>();
    for (const [nodeId, external] of this.externalNeighboursByNode) {
      if ([...external].some((id) => !present.has(id))) {
        next.add(nodeId);
      }
    }
    this.externalLinkNodeIds.set(next);
  }

  /**
   * CONTRACT (view 4 → view 3): SUBTRACTIVE tear-down of THIS trail's explosion only. Removes
   * EXACTLY the node ids the explode added (trailExplodedNodeIds) plus their now-dangling edges from
   * the accumulating graph — and NOTHING else. Base nodes and independently-expanded external nodes
   * (expandedNodeIds layer) are untouched, so contract is the precise inverse of explode and leaves
   * the base pixel-identical to view 3 before the explode (the component re-locks surviving base
   * positions and does not re-fit on a same-scope shrink). KEEPS selectedTrailId / selectedTrailDetail
   * / trailMemberIds (stay in view 3, trail still selected + magenta-highlighted); clears the explode
   * flag + the added-id tracking. No-op when nothing was exploded. The external amber cues are
   * recomputed (a removed cross-site node may re-hide an external link, restoring a node's ↗ cue).
   */
  private contractTrail(): void {
    const added = this.trailExplodedNodeIds;
    if (added.size === 0) {
      return;
    }
    const nextNodes = new Map(this.nodeMap());
    for (const id of added) {
      nextNodes.delete(id);
    }
    // Drop edges that now dangle (an endpoint was removed). Edges between two surviving nodes stay.
    const surviving = new Set(nextNodes.keys());
    const nextEdges = new Map<string, EdgeDto>();
    for (const [id, e] of this.edgeMap()) {
      if (surviving.has(e.from) && surviving.has(e.to)) {
        nextEdges.set(id, e);
      }
    }
    this.nodeMap.set(nextNodes);
    this.edgeMap.set(nextEdges);
    this.trailExplodedNodeIds = new Set();
    this.explodeActive.set(false);
    // Removing cross-site nodes may have re-hidden some external links (the ↗ cue should reappear).
    this.recomputeExternalCues();
  }

  /**
   * CHANGE 2: COLLAPSE the EXPAND-EXTERNAL layer only. SUBTRACTIVE tear-down of exactly the nodes the
   * external reveals added (externalRevealedNodeIds) plus their now-dangling edges — and NOTHING else.
   * This mirrors contractTrail() for the external layer: the base and any active trail explosion are
   * untouched. To stay strictly additive-safe a revealed id is KEPT if it also belongs to the base or
   * to the current trail explosion (so an overlapping node that another layer still owns is never
   * yanked out from under it). Clears externalRevealedNodeIds + expandedNodeIds (no external link is
   * revealed anymore), then recomputes the amber cues so revealed-then-collapsed nodes regain their ↗.
   * No-op when nothing was revealed. The base layout stays pixel-stable (a node-set shrink; surviving
   * nodes keep their prevPos, no re-fit).
   */
  collapseExternal(): void {
    const revealed = this.externalRevealedNodeIds();
    if (revealed.size === 0) {
      return;
    }
    // Only remove ids this layer alone owns: never a base node, never a node the active trail
    // explosion added (those belong to other additive layers and must survive).
    const toRemove = new Set<string>();
    for (const id of revealed) {
      if (this.baseNodeIds.has(id) || this.trailExplodedNodeIds.has(id)) {
        continue;
      }
      toRemove.add(id);
    }
    if (toRemove.size > 0) {
      const nextNodes = new Map(this.nodeMap());
      for (const id of toRemove) {
        nextNodes.delete(id);
      }
      // Drop edges that now dangle (an endpoint was removed); edges between two survivors stay.
      const surviving = new Set(nextNodes.keys());
      const nextEdges = new Map<string, EdgeDto>();
      for (const [id, e] of this.edgeMap()) {
        if (surviving.has(e.from) && surviving.has(e.to)) {
          nextEdges.set(id, e);
        }
      }
      this.nodeMap.set(nextNodes);
      this.edgeMap.set(nextEdges);
    }
    // The external layer is fully torn down regardless of whether some overlapping ids survived.
    this.externalRevealedNodeIds.set(new Set());
    this.expandedNodeIds.set(new Set());
    // Re-hiding the off-site nodes restores the amber ↗ cue on the nodes that linked to them.
    this.recomputeExternalCues();
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
        const nodes = res.neighbors.map((n) => n.node);
        // CHANGE 2: capture which neighbour node ids are genuinely NEW (not already present) BEFORE the
        // merge, so collapseExternal() can later remove EXACTLY the ids this reveal added (the precise
        // inverse), leaving base + trail layers intact. The merge is all-or-nothing at the cap; only
        // record the added ids when the merge actually applied.
        const before = this.nodeMap();
        const newlyRevealed = new Set<string>();
        for (const n of nodes) {
          if (n && !before.has(n.managedObjectId)) {
            newlyRevealed.add(n.managedObjectId);
          }
        }
        const merged = this.mergeGraph(
          nodes,
          res.neighbors.map((n) => n.via),
        );
        // Only mark the node expanded when the expansion was actually applied; an all-or-nothing
        // rejection at the cap (AC 57) leaves the graph — and the expanded set — unchanged.
        if (merged) {
          const next = new Set(this.expandedNodeIds());
          next.add(managedObjectId);
          this.expandedNodeIds.set(next);
          // CHANGE 2: accumulate the revealed-neighbour ids across successive expands so a later
          // collapseExternal removes the full set this layer added.
          const revealed = new Set(this.externalRevealedNodeIds());
          for (const id of newlyRevealed) {
            revealed.add(id);
          }
          this.externalRevealedNodeIds.set(revealed);
          // CHANGE 1: revealing neighbours may have pulled in a node's off-site links — drop the cue
          // from any node whose external neighbours are now all present.
          this.recomputeExternalCues();
        }
      });
  }

  loadTrails(): void {
    this.trailBuilder
      .listTrails()
      .pipe(catchError(() => of({ snapshotId: '', domain: '', count: 0, trails: [] })))
      .subscribe((res) => this.allTrails.set(res.trails));
  }

  /**
   * CHANGE 2a: determine the trail set the current site participates in. Uses the cheapest correct
   * path — getTrailsForObject (a by-object lookup that returns the touching trailIds) across the
   * site's seeded device ids, unioned. Cost: one /trails/by-object call per seeded device, fired once
   * at site load. Cached in siteTrailIds (the dropdown + count read the derived `trails`). A stale
   * response for a superseded site is dropped.
   */
  private computeSiteTrails(siteId: string, seeded: readonly NodeDto[]): void {
    // Defensive: a narrow test stub may omit getTrailsForObject; leave siteTrailIds null (the derived
    // `trails` then falls back to the full snapshot list) rather than crash the load.
    if (typeof this.trailBuilder.getTrailsForObject !== 'function' || seeded.length === 0) {
      return;
    }
    forkJoin(
      seeded.map((n) =>
        this.trailBuilder.getTrailsForObject(n.managedObjectId).pipe(catchError(() => of(null))),
      ),
    ).subscribe((results) => {
      if (this.pendingSiteTrailsSiteId !== siteId) {
        return; // a newer selectSite superseded this probe — ignore the stale response.
      }
      const ids = new Set<string>();
      for (const r of results) {
        if (!r || !Array.isArray(r.trailIds)) {
          continue;
        }
        for (const id of r.trailIds) {
          ids.add(id);
        }
      }
      this.siteTrailIds.set(ids);
    });
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
   * VIEW 3 — TRAIL SELECTED (HIGHLIGHT-ONLY, ZERO graph mutation when nothing needs tearing down).
   * Sets selectedTrailDetail + trailMemberIds (the full member set) so the IN-SITE portion of the
   * trail lights up magenta via applyDecoration. It does NOT mutate nodeMap/edgeMap and does NOT
   * relayout/zoom — the base looks PIXEL-IDENTICAL before/after select (the component's value-gated
   * structure effect re-decorates only because the node id-set is unchanged). The operator then opts
   * into the full cross-site path via the toggleFullPath() explode. The member set is sourced from
   * getTrail (the TrailSummary list carries no members).
   *
   * The ONLY graph mutation here is the inverse-explode of a DIFFERENT trail that was previously
   * EXPLODED: switching trails contracts the prior trail's added cross-site nodes first (so they
   * don't persist under the newly-selected trail). If no trail was exploded, the graph is untouched.
   */
  selectTrail(trailId: string): void {
    // Switching AWAY from a currently-exploded trail tears down that trail's added nodes only (the
    // base + external reveals stay). Re-selecting the SAME already-exploded trail keeps its explosion.
    if (this.explodeActive() && this.selectedTrailId() !== trailId) {
      this.contractTrail();
    }
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
        // NO explosion here — highlight-only (CHANGE 2b). Explode is explicit (explodeTrail).
      });
  }

  /**
   * VIEW 3 → VIEW 4 — EXPLODE (additive) the selected trail. Pulls every member not already in the
   * graph (with its connecting edges) so the — possibly cross-site — full path actually renders +
   * highlights. Uses the currently-selected trail's detail (set by selectTrail). Each missing member
   * is synthesized as a node from its TrailMember (so the member itself is guaranteed present) AND its
   * neighbours are fetched so its connecting edges (and the link to the rest of the path) come in.
   * forkJoin the union so the merge runs once (one structureKey change → one relayout that LOCKS the
   * existing base positions, so only the new trail nodes are placed). No-op if no trail is selected or
   * every member is already present.
   *
   * TRACKS the node ids THIS explode actually added (trailExplodedNodeIds) — only ids that were not
   * already present and were actually merged (cap-respecting) — so contract removes EXACTLY those and
   * leaves the base + external reveals intact.
   */
  explodeTrail(): void {
    const detail = this.selectedTrailDetail();
    if (!detail) {
      return;
    }
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
      // Compute which ids are genuinely NEW (not already present) BEFORE the merge, so we can track
      // exactly what this explode added (for a precise contract). The merge is all-or-nothing at the
      // cap; only record the added ids when the merge was actually applied.
      const before = this.nodeMap();
      const newlyAdded = new Set<string>();
      for (const n of nodes) {
        if (n && !before.has(n.managedObjectId)) {
          newlyAdded.add(n.managedObjectId);
        }
      }
      const merged = this.mergeGraph(nodes, edges);
      if (!merged) {
        return; // cap rejection (all-or-nothing) — nothing added, stay in view 3.
      }
      // Union with any ids a PRIOR partial explode of this same trail already added, so a re-explode
      // (e.g. after a cap-freeing collapse) still tracks the full added set for contract.
      const tracked = new Set(this.trailExplodedNodeIds);
      for (const id of newlyAdded) {
        tracked.add(id);
      }
      this.trailExplodedNodeIds = tracked;
      this.explodeActive.set(true);
      this.recomputeExternalCues();
    });
  }

  /**
   * The single "show full path" TOGGLE wired to the on-canvas explode-trail button. When the selected
   * trail is already exploded (view 4) it CONTRACTS back to view 3 (removes only this trail's added
   * nodes, keeps the selection + highlight); otherwise it EXPLODES (view 3 → view 4). No-op when no
   * trail is selected.
   */
  toggleFullPath(): void {
    if (!this.selectedTrailId()) {
      return;
    }
    if (this.explodeActive()) {
      this.contractTrail();
    } else {
      this.explodeTrail();
    }
  }

  /**
   * DESELECT the trail (view 3/4 → default site view). Contracts any active trail explosion first
   * (removes ONLY this trail's added cross-site nodes), then clears the selection + magenta highlight,
   * returning to the base — OR base + external reveals, since the EXPAND-EXTERNAL layer is INDEPENDENT
   * and is deliberately NOT torn down here (only selectSite/collapseToRoot reset external reveals).
   */
  clearTrail(): void {
    this.contractTrail();
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
