import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { TopologyStore, NODE_CAP } from './topology.store';
import { TopologyClient } from '../api/topology.client';
import { NeighborsDto, NodeDto, SiteObjectsDto } from '../api/models';
import { layerForEdge, layerForObjectType, layerForRelation, TOGGLEABLE_LAYERS } from './layer-mapper';
import { testProviders, flush } from '../../test-utils';

function store(): TopologyStore {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  return TestBed.inject(TopologyStore);
}

/** Minimal valid SiteObjectsDto for the lifecycle tests (single node, no edges). */
function objectsFor(siteId: string, nodeId: string): SiteObjectsDto {
  return {
    siteId,
    domain: 'core-ip',
    snapshotId: 'current',
    nodeCount: 1,
    edgeCount: 0,
    nodes: [
      { managedObjectId: nodeId, objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ],
    edges: [],
  };
}

describe('Topology & trails module (P1)', () => {
  it('AC 26 — geo map renders a marker per Site from the API response', async () => {
    const s = store();
    s.loadSites();
    await flush();
    expect(s.sites().length).toBe(3);
    expect(s.sites().map((x) => x.name)).toContain('London PoP');
  });

  it('AC 27 — selecting a site loads objects-at-site (nodes AND edges) for that siteId', async () => {
    const s = store();
    s.loadSites();
    await flush();
    s.selectSite('Site:LON');
    await flush();
    expect(s.selectedSiteId()).toBe('Site:LON');
    expect(s.derivedNodes().length).toBe(6);
    // 5 typed topology edges + 6 LOCATED_AT placement edges (device→Site) for the compound boxes.
    expect(s.derivedEdges().length).toBe(11);
    expect(s.hasGraph()).toBe(true);
    // nodeSiteMap is populated at root from the LOCATED_AT edges (single site box).
    expect(s.distinctSiteIds()).toEqual(['Site:LON']);
  });

  it('AC 28 — layer is derived from objectType; toggling hides matching edges; all-off shows only nodes', async () => {
    expect(layerForObjectType('FiberSpan')).toBe('fiber');
    expect(layerForObjectType('Interface')).toBe('IP');
    expect(layerForObjectType('Router')).toBe('IGP');
    expect(layerForObjectType('LSP')).toBe('LSP');
    expect(layerForObjectType('Service')).toBe('service');
    expect(layerForObjectType('Unknown')).toBe('other');

    const s = store();
    s.selectSite('Site:LON');
    await flush();
    const edgeCount = s.visibleEdges().length;
    expect(edgeCount).toBeGreaterThan(0);
    // turn off every layer -> no edges visible, nodes still present
    for (const layer of ['fiber', 'IP', 'IGP', 'LSP', 'service', 'other'] as const) {
      s.setLayerVisible(layer, false);
    }
    expect(s.visibleEdges().length).toBe(0);
    expect(s.derivedNodes().length).toBe(6);
  });

  // ── #263 — every rendered edge resolves to one of the FIVE toggleable layers ────────────────
  it('AC 28 (#263) — the typed edge `relation` maps to a toggleable layer (no edge left in `other`)', () => {
    // Every Topology §5 relation (and the Core-IP snapshot relations) resolves to a layer.
    expect(layerForRelation('MEMBER_OF')).toBe('fiber'); // SRLG = shared-risk fiber grouping
    expect(layerForRelation('RIDES_ON')).toBe('fiber');
    expect(layerForRelation('HOSTED_ON')).toBe('IGP'); // structural chassis containment
    expect(layerForRelation('HOSTS')).toBe('IP');
    expect(layerForRelation('ADJACENCY_OVER')).toBe('IGP');
    expect(layerForRelation('TRAVERSES')).toBe('LSP');
    expect(layerForRelation('SERVES')).toBe('service');
    expect(layerForRelation('LOCATED_AT')).toBe('IGP'); // device→Site placement is structural
    // The two relations that previously fell through to `other` now have a real layer.
    expect(layerForEdge({ edgeId: 'x', from: 'FiberSpan:a', to: 'SRLG:s', relation: 'MEMBER_OF', domain: 'd', snapshotId: 'c', attributes: {} })).toBe('fiber');
    expect(layerForEdge({ edgeId: 'y', from: 'LineCard:l', to: 'Router:r', relation: 'HOSTED_ON', domain: 'd', snapshotId: 'c', attributes: {} })).toBe('IGP');
  });

  it('AC 28 (#263) — toggling ONLY the five logical layers off leaves 0 edges; the SRLG/containment edges are governed too', async () => {
    const s = store();
    s.selectSite('Site:LON');
    await flush();

    // The fixture includes the previously-unmapped SRLG MEMBER_OF (e-4) + HOSTED_ON containment (e-5).
    const layers = s.derivedEdges().map((e) => e.derivedLayer);
    expect(layers).not.toContain('other'); // no rendered edge is left un-toggleable
    const member = s.derivedEdges().find((e) => e.relation === 'MEMBER_OF');
    const hosted = s.derivedEdges().find((e) => e.relation === 'HOSTED_ON');
    expect(member?.derivedLayer).toBe('fiber');
    expect(hosted?.derivedLayer).toBe('IGP');

    // Toggling JUST 'fiber' off hides the SRLG edge specifically (mapping is exercised, not aggregate).
    s.setLayerVisible('fiber', false);
    expect(s.visibleEdges().some((e) => e.relation === 'MEMBER_OF')).toBe(false);
    s.setLayerVisible('fiber', true);
    // Toggling JUST 'IGP' off hides the HOSTED_ON containment edge specifically.
    s.setLayerVisible('IGP', false);
    expect(s.visibleEdges().some((e) => e.relation === 'HOSTED_ON')).toBe(false);
    s.setLayerVisible('IGP', true);

    // Turning off ONLY the five toggleable layers (NOT 'other') → 0 edges, all nodes remain.
    for (const layer of TOGGLEABLE_LAYERS) {
      s.setLayerVisible(layer, false);
    }
    expect(s.visibleEdges().length).toBe(0);
    expect(s.derivedNodes().length).toBe(6);
  });

  it('AC 29 — node detail shows vendor/model/equipmentType + unknown keys generic', async () => {
    const s = store();
    s.selectSite('Site:LON');
    await flush();
    s.selectNode('Router:lon-r1');
    await flush();
    const node = s.selectedNode();
    expect(node?.attributes['vendor']).toBe('Acme');
    expect(node?.attributes['model']).toBe('R8000');
    expect(node?.attributes['equipmentType']).toBe('router');
    expect(node?.attributes['slotCount']).toBe(16); // unknown key still present
  });

  it('AC 30 — edge detail shows linkType/capacity from SiteObjectsDto.edges', async () => {
    const s = store();
    s.selectSite('Site:LON');
    await flush();
    s.selectEdge('e-2');
    const edge = s.selectedEdge();
    expect(edge?.attributes['linkType']).toBe('fiber');
    expect(edge?.attributes['capacity']).toBe('100G');
  });

  it('AC 31 — trail clusters loaded from listTrails for overlays', async () => {
    const s = store();
    s.selectSite('Site:LON');
    await flush();
    expect(s.trails().length).toBe(2);
    expect(s.trails().map((t) => t.trailId)).toContain('TR-7');
  });

  it('AC 32 — selecting a multi-trail device highlights all its trails', async () => {
    const s = store();
    s.selectSite('Site:LON');
    await flush();
    s.selectNode('Router:lon-r1');
    await flush();
    expect([...s.highlightedTrailIds()].sort()).toEqual(['TR-7', 'TR-8']);
  });

  // ── Load/render lifecycle (#253) — graphLoading gates the device-graph render deterministically ──
  it('graphLoading is true while objects-at-site is in flight and clears to false on success (no race)', async () => {
    const subject = new Subject<SiteObjectsDto>();
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: TopologyClient, useValue: { objectsAtSite: () => subject.asObservable(), listSites: () => new Subject() } },
      ],
    });
    const s = TestBed.inject(TopologyStore);

    s.selectSite('Site:LON');
    // In flight: loading is TRUE and the graph is cleared — the @if keeps the graph on "Loading…".
    expect(s.graphLoading()).toBe(true);
    expect(s.hasGraph()).toBe(false);

    // Response resolves → loading clears deterministically and the graph populates the render.
    subject.next(objectsFor('Site:LON', 'Router:r1'));
    subject.complete();
    await flush();
    expect(s.graphLoading()).toBe(false);
    expect(s.derivedNodes().length).toBe(1);
  });

  it('graphLoading clears to false even when objects-at-site ERRORS (graph never stays stuck on Loading…)', async () => {
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: TopologyClient, useValue: { objectsAtSite: () => throwError(() => new Error('boom')), listSites: () => new Subject() } },
      ],
    });
    const s = TestBed.inject(TopologyStore);

    s.selectSite('Site:LON');
    await flush();
    // Error path: catchError emits null, the subscribe callback still runs → loading cleared.
    expect(s.graphLoading()).toBe(false);
    expect(s.hasGraph()).toBe(false);
  });

  it('a stale objects-at-site response for a superseded site does not clobber the current load', async () => {
    const first = new Subject<SiteObjectsDto>();
    const second = new Subject<SiteObjectsDto>();
    let call = 0;
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        {
          provide: TopologyClient,
          useValue: { objectsAtSite: () => (call++ === 0 ? first.asObservable() : second.asObservable()), listSites: () => new Subject() },
        },
      ],
    });
    const s = TestBed.inject(TopologyStore);

    s.selectSite('Site:A');
    s.selectSite('Site:B'); // supersedes the first request
    // The first (stale) request resolves LATE — it must be ignored.
    first.next(objectsFor('Site:A', 'A:1'));
    await flush();
    expect(s.graphLoading()).toBe(true); // still loading B, the stale A response was dropped
    expect(s.hasGraph()).toBe(false);

    second.next(objectsFor('Site:B', 'B:1'));
    await flush();
    expect(s.graphLoading()).toBe(false);
    expect(s.derivedNodes()[0].managedObjectId).toBe('B:1');
  });
});

describe('Topology EXPLORER — accumulating graph, expand, cross-site trail explode (new behaviour)', () => {
  it('expandNode merges a node\'s neighbours into the accumulating graph (grows, dedupes overlap)', async () => {
    const s = store();
    s.loadSites();
    await flush();
    s.selectSite('Site:LON');
    await flush();
    const before = s.derivedNodes().length;
    expect(before).toBe(6);

    s.expandNode('Router:lon-r1'); // pulls Router:fra-r1 (+ Site:FRA container, filtered out)
    await flush();
    const after = s.derivedNodes().length;
    // FAILS on the old single-objects store (no expandNode / no neighbour fan-out): the graph grew.
    expect(after).toBeGreaterThan(before);
    expect(s.derivedNodes().map((n) => n.managedObjectId)).toContain('Router:fra-r1');
    expect(s.expandedNodeIds().has('Router:lon-r1')).toBe(true);

    // Expanding the SAME node again must not duplicate (dedupe by id) — count stays stable.
    const afterFirst = s.derivedNodes().length;
    s.expandNode('Router:lon-r1');
    await flush();
    expect(s.derivedNodes().length).toBe(afterFirst);
  });

  it('nodeSiteMap derives device→site from LOCATED_AT; distinct-site count goes 1→2 after cross-site expand', async () => {
    const s = store();
    s.loadSites();
    await flush();
    s.selectSite('Site:LON');
    await flush();
    // At root: every LON device maps to Site:LON (single box) via the LOCATED_AT placement edges.
    expect(s.distinctSiteIds()).toEqual(['Site:LON']);
    expect(s.nodeSiteMap().get('Router:lon-r1')).toBe('Site:LON');

    s.expandNode('Router:lon-r1'); // crosses into Site:FRA (Router:fra-r1 LOCATED_AT Site:FRA)
    await flush();
    // FAILS on the old static single-site store: the second site box appears.
    expect(s.distinctSiteIds().sort()).toEqual(['Site:FRA', 'Site:LON']);
    expect(s.nodeSiteMap().get('Router:fra-r1')).toBe('Site:FRA');
  });

  it('selectTrail highlights the FULL member set (not the hollow 1) and explodes cross-site members into the graph', async () => {
    const s = store();
    s.loadSites();
    await flush();
    s.selectSite('Site:LON');
    await flush();
    // FRA member is NOT in the rooted LON graph yet.
    expect(s.nodeMap().has('Router:fra-r1')).toBe(false);

    s.selectTrail('TR-7'); // members span LON + FRA (memberCount 4)
    await flush();

    // The full member set lights up — FAILS on the old hollow highlight (only the selected node).
    expect(s.trailMemberIds().size).toBe(4);
    expect(s.trailMemberIds().size).toBeGreaterThan(1);
    expect(s.selectedTrailId()).toBe('TR-7');
    expect(s.selectedTrailDetail()?.memberCount).toBe(4);

    // EXPLODE: the cross-site member was pulled into the accumulating graph.
    expect(s.nodeMap().has('Router:fra-r1')).toBe(true);
    expect(s.derivedNodes().map((n) => n.managedObjectId)).toContain('Router:fra-r1');

    // clearTrail drops the selection (keeps exploded nodes).
    s.clearTrail();
    expect(s.selectedTrailId()).toBeNull();
    expect(s.trailMemberIds().size).toBe(0);
    expect(s.nodeMap().has('Router:fra-r1')).toBe(true);
  });

  it('NODE_CAP bounds the accumulating graph: capReached flips and size never exceeds the cap', async () => {
    // Stub a TopologyClient whose neighbours return MORE than NODE_CAP fresh nodes in one merge.
    const many: NeighborsDto = {
      managedObjectId: 'seed',
      domain: 'core-ip',
      neighbors: Array.from({ length: NODE_CAP + 50 }, (_, i) => ({
        node: { managedObjectId: `n-${i}`, objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} } as NodeDto,
        via: { edgeId: `ne-${i}`, from: 'seed', to: `n-${i}`, relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      })),
    };
    const seed: SiteObjectsDto = objectsFor('Site:LON', 'seed');
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        {
          provide: TopologyClient,
          useValue: {
            listSites: () => of({ domain: 'core-ip', snapshotId: 'current', count: 0, sites: [] }),
            objectsAtSite: () => of(seed),
            neighbors: () => of(many),
          },
        },
      ],
    });
    const s = TestBed.inject(TopologyStore);
    s.selectSite('Site:LON');
    await flush();
    s.expandNode('seed');
    await flush();

    expect(s.capReached()).toBe(true);
    // Size is bounded at the cap (nodeMap counts the seed + capped fresh nodes, never above NODE_CAP).
    expect(s.nodeMap().size).toBeLessThanOrEqual(NODE_CAP);
    // A further expand no-ops once capped.
    const sizeAtCap = s.nodeMap().size;
    s.expandNode('seed');
    await flush();
    expect(s.nodeMap().size).toBe(sizeAtCap);
  });
});
