import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { TopologyClient } from '../api/topology.client';
import { TrailBuilderClient } from '../api/trail-builder.client';
import { EdgeDto, LogicalLayer, NodeDto, SiteDto, SiteObjectsDto, TrailSummary } from '../api/models';
import { ALL_LAYERS, layerForObjectType } from './layer-mapper';

export interface DerivedNode extends NodeDto {
  derivedLayer: LogicalLayer;
}
export interface DerivedEdge extends EdgeDto {
  derivedLayer: LogicalLayer;
}

/** Shared topology + trails state (signals). Drives the geo map, site graph, layer toggles,
 *  attribute panel, and trail overlays/highlights (spec tasks 6-9, AC 26-32). */
@Injectable({ providedIn: 'root' })
export class TopologyStore {
  private readonly topo = inject(TopologyClient);
  private readonly trailBuilder = inject(TrailBuilderClient);

  readonly sites = signal<SiteDto[]>([]);
  readonly sitesLoading = signal<boolean>(false);
  readonly selectedSiteId = signal<string | null>(null);
  readonly objects = signal<SiteObjectsDto | null>(null);
  readonly graphLoading = signal<boolean>(false);

  readonly visibleLayers = signal<ReadonlySet<LogicalLayer>>(new Set(ALL_LAYERS));
  readonly selectedObjectId = signal<string | null>(null);
  readonly selectedEdgeId = signal<string | null>(null);

  readonly trails = signal<TrailSummary[]>([]);
  readonly highlightedTrailIds = signal<ReadonlySet<string>>(new Set());
  readonly activeTrailId = signal<string | null>(null);

  /** The siteId of the in-flight objects-at-site request; stale responses are dropped (guards
   *  against a slower prior request clobbering the current site / clearing loading wrongly). */
  private pendingObjectsSiteId: string | null = null;

  readonly derivedNodes = computed<DerivedNode[]>(() =>
    (this.objects()?.nodes ?? []).map((n) => ({ ...n, derivedLayer: layerForObjectType(n.objectType) })),
  );
  readonly derivedEdges = computed<DerivedEdge[]>(() =>
    (this.objects()?.edges ?? []).map((e) => ({
      ...e,
      derivedLayer: layerForObjectType(this.endpointObjectType(e)),
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

  selectSite(siteId: string): void {
    this.selectedSiteId.set(siteId);
    this.graphLoading.set(true);
    this.objects.set(null);
    this.selectedObjectId.set(null);
    this.selectedEdgeId.set(null);
    this.highlightedTrailIds.set(new Set());
    // Tag this request so a slower/stale in-flight response for a previously-selected site can
    // never clobber the current one or wrongly clear the loading state (single stable load per
    // selected site). The loading flag is cleared deterministically on BOTH success and error
    // (catchError emits null → the subscribe callback still runs), so the graph never stays stuck
    // on "Loading site graph…".
    this.pendingObjectsSiteId = siteId;
    this.topo
      .objectsAtSite(siteId)
      .pipe(catchError(() => of(null)))
      .subscribe((res) => {
        if (this.pendingObjectsSiteId !== siteId) {
          return; // a newer selectSite superseded this request — ignore the stale response.
        }
        this.objects.set(res);
        this.graphLoading.set(false);
      });
    this.loadTrails();
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

  activateTrail(trailId: string): void {
    this.activeTrailId.set(trailId);
    this.highlightedTrailIds.set(new Set([trailId]));
  }

  private endpointObjectType(edge: EdgeDto): string {
    // Derive the edge's logical layer from its endpoints' typed managedObjectId prefix.
    const prefix = edge.from.split(':')[0];
    return prefix || edge.relation;
  }
}
