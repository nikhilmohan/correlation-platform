import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Subject, throwError } from 'rxjs';
import { TopologyStore } from './topology.store';
import { TopologyClient } from '../api/topology.client';
import { SiteObjectsDto } from '../api/models';
import { layerForObjectType } from './layer-mapper';
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
    s.selectSite('Site:LON');
    await flush();
    expect(s.selectedSiteId()).toBe('Site:LON');
    expect(s.objects()?.nodes.length).toBe(4);
    expect(s.objects()?.edges.length).toBe(3);
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
    expect(s.derivedNodes().length).toBe(4);
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
    // In flight: loading is TRUE and objects are cleared — the @if keeps the graph on "Loading…".
    expect(s.graphLoading()).toBe(true);
    expect(s.objects()).toBeNull();

    // Response resolves → loading clears deterministically and objects() populates the render.
    subject.next(objectsFor('Site:LON', 'Router:r1'));
    subject.complete();
    await flush();
    expect(s.graphLoading()).toBe(false);
    expect(s.objects()?.nodes.length).toBe(1);
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
    expect(s.objects()).toBeNull();
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
    expect(s.objects()).toBeNull();

    second.next(objectsFor('Site:B', 'B:1'));
    await flush();
    expect(s.graphLoading()).toBe(false);
    expect(s.objects()?.nodes[0].managedObjectId).toBe('B:1');
  });
});
