import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of, Subject } from 'rxjs';
import { TopologyStore } from './topology.store';
import { TopologyClient } from '../api/topology.client';
import { TrailBuilderClient } from '../api/trail-builder.client';
import {
  ListTrailsResponse,
  NeighborsDto,
  SiteListDto,
  SiteObjectsDto,
  TrailDetail,
  TrailsForObjectResponse,
} from '../api/models';
import { testProviders, flush } from '../../test-utils';

/**
 * topology-v2 operator-feedback changes:
 *  - CHANGE 1: externalLinkNodeIds — the amber ↗ cue gate. A node is flagged ONLY when it has an
 *    off-site neighbour that is not yet in the graph; a leaf with nothing external is never flagged;
 *    a node drops the flag once its external neighbours are revealed (expand).
 *  - CHANGE 2a: trails are SITE-SCOPED — only trails the current site participates in are listed.
 *  - CHANGE 2b: selectTrail is highlight-only (covered in topology.spec); CHANGE 2c explodeTrail.
 */

const SITES: SiteListDto = {
  domain: 'core-ip',
  snapshotId: 'current',
  count: 2,
  sites: [
    { siteId: 'Site:A', name: 'Alpha', latitude: 0, longitude: 0, region: 'R' },
    { siteId: 'Site:B', name: 'Beta', latitude: 1, longitude: 1, region: 'R' },
  ],
};

// Site:A has two devices. A1 links OFF-SITE to B1 (external); A2 is a pure leaf (no external link).
const SITE_A: SiteObjectsDto = {
  siteId: 'Site:A',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 2,
  edgeCount: 1,
  nodes: [
    { managedObjectId: 'Dev:A1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { managedObjectId: 'Dev:A2', objectType: 'Interface', domain: 'core-ip', snapshotId: 'current', attributes: {} },
  ],
  edges: [
    { edgeId: 'a-int', from: 'Dev:A1', to: 'Dev:A2', relation: 'HAS_PORT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { edgeId: 'loc-a1', from: 'Dev:A1', to: 'Site:A', relation: 'LOCATED_AT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { edgeId: 'loc-a2', from: 'Dev:A2', to: 'Site:A', relation: 'LOCATED_AT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
  ],
};

const NEIGHBORS: Record<string, NeighborsDto> = {
  // A1: an in-site neighbour (A2) + an OFF-SITE neighbour (B1) + the site container (ignored).
  'Dev:A1': {
    managedObjectId: 'Dev:A1',
    domain: 'core-ip',
    neighbors: [
      { node: SITE_A.nodes[1], via: SITE_A.edges[0] },
      {
        node: { managedObjectId: 'Dev:B1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
        via: { edgeId: 'x-ab', from: 'Dev:A1', to: 'Dev:B1', relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
      {
        node: { managedObjectId: 'Site:B', objectType: 'Site', domain: 'core-ip', snapshotId: 'current', attributes: {} },
        via: { edgeId: 'loc-b1', from: 'Dev:B1', to: 'Site:B', relation: 'LOCATED_AT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
    ],
  },
  // A2: only an in-site neighbour — NO external link → never flagged.
  'Dev:A2': {
    managedObjectId: 'Dev:A2',
    domain: 'core-ip',
    neighbors: [{ node: SITE_A.nodes[0], via: SITE_A.edges[0] }],
  },
};

function neighborsFor(id: string): NeighborsDto {
  return NEIGHBORS[id] ?? { managedObjectId: id, domain: 'core-ip', neighbors: [] };
}

const EMPTY_TRAILS: ListTrailsResponse = { snapshotId: 'current', domain: 'core-ip', count: 0, trails: [] };

function configureStore(opts?: {
  trails?: ListTrailsResponse;
  trailsForObject?: (id: string) => TrailsForObjectResponse;
  getTrail?: TrailDetail;
}): TopologyStore {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      {
        provide: TopologyClient,
        useValue: {
          listSites: () => of(SITES),
          objectsAtSite: () => of(SITE_A),
          neighbors: (id: string) => of(neighborsFor(id)),
        },
      },
      {
        provide: TrailBuilderClient,
        useValue: {
          listTrails: () => of(opts?.trails ?? EMPTY_TRAILS),
          getTrailsForObject: (id: string) =>
            of(opts?.trailsForObject?.(id) ?? { managedObjectId: id, domain: 'core-ip', trailIds: [] }),
          getTrail: () =>
            of(
              opts?.getTrail ?? {
                trailId: 'TR-X',
                domain: 'core-ip',
                snapshotId: 'current',
                memberCount: 0,
                members: [],
              },
            ),
        },
      },
    ],
  });
  return TestBed.inject(TopologyStore);
}

describe('CHANGE 1 — externalLinkNodeIds gates the amber ↗ cue', () => {
  it('flags ONLY nodes with an off-site neighbour; a pure in-site leaf is never flagged', async () => {
    const s = configureStore();
    s.loadSites();
    await flush();
    s.selectSite('Site:A');
    await flush();

    // A1 has an off-site neighbour (Dev:B1, not in-site) → flagged. A2 is in-site-only → not flagged.
    expect(s.externalLinkNodeIds().has('Dev:A1')).toBe(true);
    expect(s.externalLinkNodeIds().has('Dev:A2')).toBe(false);
    // The site container is never flagged.
    expect(s.externalLinkNodeIds().has('Site:A')).toBe(false);
  });

  it('drops the cue from a node once its external neighbours are revealed (expand)', async () => {
    const s = configureStore();
    s.loadSites();
    await flush();
    s.selectSite('Site:A');
    await flush();
    expect(s.externalLinkNodeIds().has('Dev:A1')).toBe(true);

    // Expanding A1 pulls Dev:B1 into the graph — A1's only external neighbour is now present.
    s.expandNode('Dev:A1');
    await flush();
    expect(s.nodeMap().has('Dev:B1')).toBe(true);
    // The cue is dropped (nothing external remains hidden for A1).
    expect(s.externalLinkNodeIds().has('Dev:A1')).toBe(false);
  });

  it('re-rooting clears the previous site\'s external-cue set before the new probe resolves', async () => {
    // A DEFERRED objectsAtSite so the synchronous clear in selectSite is observable before the
    // (forkJoin) probe re-resolves — proves selectSite resets the cue set on re-root.
    const subject = new Subject<SiteObjectsDto>();
    let call = 0;
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        {
          provide: TopologyClient,
          useValue: {
            listSites: () => of(SITES),
            objectsAtSite: () => (call++ === 0 ? of(SITE_A) : subject.asObservable()),
            neighbors: (id: string) => of(neighborsFor(id)),
          },
        },
        {
          provide: TrailBuilderClient,
          useValue: {
            listTrails: () => of(EMPTY_TRAILS),
            getTrailsForObject: (id: string) => of({ managedObjectId: id, domain: 'core-ip', trailIds: [] }),
            getTrail: () => of({ trailId: 'x', domain: 'core-ip', snapshotId: 'current', memberCount: 0, members: [] }),
          },
        },
      ],
    });
    const s = TestBed.inject(TopologyStore);
    s.loadSites();
    await flush();
    s.selectSite('Site:A'); // synchronous of(SITE_A) → probe resolves → cue populated
    await flush();
    expect(s.externalLinkNodeIds().size).toBeGreaterThan(0);

    // Re-root: objectsAtSite for Site:B is deferred, so the cue set is cleared and stays empty until
    // the new load resolves.
    s.selectSite('Site:B');
    expect(s.externalLinkNodeIds().size).toBe(0);
  });
});

describe('CHANGE 2a — trails are scoped to the trails the current site participates in', () => {
  it('lists ONLY trails whose by-object membership intersects the site\'s devices', async () => {
    const trails: ListTrailsResponse = {
      snapshotId: 'current',
      domain: 'core-ip',
      count: 3,
      trails: [
        { trailId: 'TR-A', domain: 'core-ip', memberCount: 2 },
        { trailId: 'TR-B', domain: 'core-ip', memberCount: 2 },
        { trailId: 'TR-OTHER', domain: 'core-ip', memberCount: 2 },
      ],
    };
    // Site:A's devices touch TR-A (via A1) and TR-B (via A2) — but NOT TR-OTHER.
    const s = configureStore({
      trails,
      trailsForObject: (id) => ({
        managedObjectId: id,
        domain: 'core-ip',
        trailIds: id === 'Dev:A1' ? ['TR-A'] : id === 'Dev:A2' ? ['TR-B'] : [],
      }),
    });
    s.loadSites();
    await flush();
    s.selectSite('Site:A');
    await flush();

    const ids = s.trails().map((t) => t.trailId).sort();
    expect(ids).toEqual(['TR-A', 'TR-B']); // TR-OTHER (no in-site member) is excluded
    expect(s.siteTrailIds()).not.toBeNull();
  });

  it('a site with no participating trails yields an empty scoped list', async () => {
    const trails: ListTrailsResponse = {
      snapshotId: 'current',
      domain: 'core-ip',
      count: 1,
      trails: [{ trailId: 'TR-OTHER', domain: 'core-ip', memberCount: 2 }],
    };
    const s = configureStore({ trails, trailsForObject: (id) => ({ managedObjectId: id, domain: 'core-ip', trailIds: [] }) });
    s.loadSites();
    await flush();
    s.selectSite('Site:A');
    await flush();
    expect(s.trails()).toEqual([]);
  });
});
