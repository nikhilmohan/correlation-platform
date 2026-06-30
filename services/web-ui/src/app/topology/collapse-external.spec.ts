import { TestBed } from '@angular/core/testing';
import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { TopologyStore } from './topology.store';
import { NeighborsDto, SiteObjectsDto, TrailDetail } from '../api/models';
import { flush } from '../../test-utils';

/**
 * CHANGE 2 — the EXPAND-EXTERNAL collapse contract. Revealing a device's off-site links via
 * expandNode() must TRACK the newly-revealed neighbour node ids in `externalRevealedNodeIds`, and
 * collapseExternal() must remove EXACTLY those nodes (and their now-dangling edges) — leaving the
 * base seed and any active trail explosion untouched. This mirrors the trail contract for the
 * independent external layer.
 *
 * Seed = two in-site routers. Expanding seed-0 reveals an OFF-SITE neighbour (ext-1). A separate
 * trail explode adds a cross-site node (trail-x). collapseExternal() must drop ONLY ext-1.
 */

const SEED: SiteObjectsDto = {
  siteId: 'Site:LON',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 2,
  edgeCount: 0,
  nodes: [
    { managedObjectId: 'seed-0', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { managedObjectId: 'seed-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
  ],
  edges: [],
};

/** seed-0's neighbours: one OFF-SITE node (ext-1) joined by an IP link. */
const NEIGHBORS_SEED0: NeighborsDto = {
  managedObjectId: 'seed-0',
  domain: 'core-ip',
  neighbors: [
    {
      node: { managedObjectId: 'ext-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      via: { edgeId: 'e-seed0-ext1', from: 'seed-0', to: 'ext-1', relation: 'CONNECTS_TO', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    },
  ],
};

/** A trail whose cross-site member trail-x is not in the seed (drives explode → trailExplodedNodeIds). */
const TRAIL_DETAIL: TrailDetail = {
  trailId: 'T-1',
  domain: 'core-ip',
  snapshotId: 'current',
  members: [
    { managedObjectId: 'seed-0', objectType: 'Router' },
    { managedObjectId: 'trail-x', objectType: 'Router' },
  ],
} as unknown as TrailDetail;

/** trail-x's neighbours (empty extra) so explode just adds the member node trail-x. */
const NEIGHBORS_TRAILX: NeighborsDto = {
  managedObjectId: 'trail-x',
  domain: 'core-ip',
  neighbors: [],
};

function makeInterceptor() {
  const interceptor = (req: HttpRequest<unknown>, _next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
    const path = req.url.split('?')[0];
    if (path.endsWith('/objects')) {
      return of(new HttpResponse({ status: 200, body: SEED }));
    }
    if (path.endsWith('/neighbors')) {
      if (path.includes('seed-0')) {
        return of(new HttpResponse({ status: 200, body: NEIGHBORS_SEED0 }));
      }
      if (path.includes('trail-x')) {
        return of(new HttpResponse({ status: 200, body: NEIGHBORS_TRAILX }));
      }
      // seed-1 / ext-1 / others: no neighbours (keeps the probe + explode deterministic).
      return of(new HttpResponse({ status: 200, body: { managedObjectId: 'x', domain: 'core-ip', neighbors: [] } }));
    }
    if (path.endsWith('/sites')) {
      return of(new HttpResponse({ status: 200, body: { domain: 'core-ip', snapshotId: 'current', count: 0, sites: [] } }));
    }
    if (path.includes('/trails/') && path.endsWith('/T-1')) {
      return of(new HttpResponse({ status: 200, body: TRAIL_DETAIL }));
    }
    if (path.includes('by-object') || path.includes('/object/')) {
      return of(new HttpResponse({ status: 200, body: { managedObjectId: 'x', domain: 'core-ip', trailIds: [] } }));
    }
    if (path.includes('/trails')) {
      return of(new HttpResponse({ status: 200, body: { snapshotId: 'current', domain: 'core-ip', count: 0, trails: [] } }));
    }
    return of(new HttpResponse({ status: 200, body: {} }));
  };
  return interceptor;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), provideHttpClient(withInterceptors([makeInterceptor()]))],
  });
  return TestBed.inject(TopologyStore);
}

describe('CHANGE 2 — externalRevealedNodeIds tracking + collapseExternal', () => {
  it('expandNode tracks the NEWLY-REVEALED neighbour ids (not the source node)', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();
    expect(store.externalRevealedNodeIds().size).toBe(0);

    store.expandNode('seed-0');
    await flush();

    // ext-1 was revealed; seed-0 (the source, already present) is NOT in the revealed set.
    expect([...store.externalRevealedNodeIds()]).toEqual(['ext-1']);
    expect(store.externalRevealedNodeIds().has('seed-0')).toBe(false);
    expect(store.nodeMap().has('ext-1')).toBe(true);
  });

  it('collapseExternal removes ONLY the revealed external nodes + their dangling edges', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();
    store.expandNode('seed-0');
    await flush();

    expect(store.nodeMap().has('ext-1')).toBe(true);
    expect(store.edgeMap().has('e-seed0-ext1')).toBe(true);

    store.collapseExternal();

    // The revealed external node + its dangling edge are gone…
    expect(store.nodeMap().has('ext-1')).toBe(false);
    expect(store.edgeMap().has('e-seed0-ext1')).toBe(false);
    // …the external layer is fully torn down…
    expect(store.externalRevealedNodeIds().size).toBe(0);
    expect(store.expandedNodeIds().size).toBe(0);
    // …and the BASE seed is untouched.
    expect(store.nodeMap().has('seed-0')).toBe(true);
    expect(store.nodeMap().has('seed-1')).toBe(true);
  });

  it('collapseExternal leaves an ACTIVE TRAIL explosion untouched (independent layers)', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();

    // Reveal an external link AND explode a trail (two independent additive layers).
    store.expandNode('seed-0');
    await flush();
    store.selectTrail('T-1');
    await flush();
    store.explodeTrail();
    await flush();

    expect(store.nodeMap().has('ext-1')).toBe(true); // external layer
    expect(store.nodeMap().has('trail-x')).toBe(true); // trail layer

    store.collapseExternal();

    // ONLY the external node is removed; the trail-exploded node survives.
    expect(store.nodeMap().has('ext-1')).toBe(false);
    expect(store.nodeMap().has('trail-x')).toBe(true);
    expect(store.explodeActive()).toBe(true);
    expect(store.nodeMap().has('seed-0')).toBe(true);
  });

  it('collapseExternal is a no-op when nothing was revealed', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();
    const before = store.nodeMap();

    store.collapseExternal();

    expect(store.nodeMap()).toBe(before); // same reference — untouched
    expect(store.externalRevealedNodeIds().size).toBe(0);
  });

  it('after collapseExternal the amber ↗ cue is recomputed (re-hidden external link regains its cue)', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();
    // The site-load probe flagged seed-0 as having an off-site neighbour (ext-1).
    expect(store.externalLinkNodeIds().has('seed-0')).toBe(true);

    store.expandNode('seed-0');
    await flush();
    // ext-1 now present → seed-0's external link is fully revealed → cue dropped.
    expect(store.externalLinkNodeIds().has('seed-0')).toBe(false);

    store.collapseExternal();
    // ext-1 re-hidden → seed-0 regains its ↗ cue.
    expect(store.externalLinkNodeIds().has('seed-0')).toBe(true);
  });
});

/**
 * PER-NODE COLLAPSE (the on-node "−" affordance). expandNode() must record which neighbour ids each
 * SOURCE revealed (provenance), and collapseNodeExternal(sourceId) must remove EXACTLY that source's
 * exclusively-revealed nodes — never a base node, never a node the active trail explosion added, and
 * never a node another still-expanded source also revealed (a shared reveal survives).
 */
describe('per-node collapse — revealedByNode tracking + collapseNodeExternal', () => {
  it('collapseNodeExternal removes ONLY that source’s revealed nodes + flips it out of expandedNodeIds', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();

    store.expandNode('seed-0');
    await flush();
    expect(store.expandedNodeIds().has('seed-0')).toBe(true);
    expect(store.nodeMap().has('ext-1')).toBe(true);

    store.collapseNodeExternal('seed-0');

    // The source's revealed node + its dangling edge are gone…
    expect(store.nodeMap().has('ext-1')).toBe(false);
    expect(store.edgeMap().has('e-seed0-ext1')).toBe(false);
    // …the source is no longer "expanded" (so its badge flips back to "+")…
    expect(store.expandedNodeIds().has('seed-0')).toBe(false);
    // …the flat reveal set drops it (no source reveals anything now)…
    expect(store.externalRevealedNodeIds().size).toBe(0);
    // …the base seed is untouched…
    expect(store.nodeMap().has('seed-0')).toBe(true);
    expect(store.nodeMap().has('seed-1')).toBe(true);
    // …and the amber ↗ cue returns on the source.
    expect(store.externalLinkNodeIds().has('seed-0')).toBe(true);
  });

  it('collapseNodeExternal is a no-op when the source was never expanded', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();
    const before = store.nodeMap();

    store.collapseNodeExternal('seed-1');

    expect(store.nodeMap()).toBe(before); // same reference — untouched
    expect(store.expandedNodeIds().size).toBe(0);
  });

  it('collapseNodeExternal leaves an active TRAIL explosion untouched', async () => {
    const store = setup();
    store.selectSite('Site:LON');
    await flush();

    store.expandNode('seed-0');
    await flush();
    store.selectTrail('T-1');
    await flush();
    store.explodeTrail();
    await flush();

    expect(store.nodeMap().has('ext-1')).toBe(true); // external layer
    expect(store.nodeMap().has('trail-x')).toBe(true); // trail layer

    store.collapseNodeExternal('seed-0');

    // ONLY this source's external node is removed; the trail-exploded node survives.
    expect(store.nodeMap().has('ext-1')).toBe(false);
    expect(store.nodeMap().has('trail-x')).toBe(true);
    expect(store.explodeActive()).toBe(true);
  });

  it('a node revealed by TWO sources survives collapse of one of them (shared reveal)', () => {
    const store = setup();
    // Drive the provenance + maps directly so the shared-reveal logic is tested deterministically,
    // independent of which fixture neighbours overlap. Two sources (A, B) both reveal shared-1; B
    // also exclusively reveals only-b.
    store.nodeMap.set(
      new Map([
        ['A', { managedObjectId: 'A', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }],
        ['B', { managedObjectId: 'B', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }],
        ['shared-1', { managedObjectId: 'shared-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }],
        ['only-b', { managedObjectId: 'only-b', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }],
      ]),
    );
    store.edgeMap.set(new Map());
    store.expandedNodeIds.set(new Set(['A', 'B']));
    store.externalRevealedNodeIds.set(new Set(['shared-1', 'only-b']));
    // Provenance: A→{shared-1}, B→{shared-1, only-b}.
    (store as unknown as { revealedByNode: Map<string, Set<string>> }).revealedByNode = new Map([
      ['A', new Set(['shared-1'])],
      ['B', new Set(['shared-1', 'only-b'])],
    ]);

    // Collapse A: shared-1 is still required by B (still expanded) → it must SURVIVE.
    store.collapseNodeExternal('A');

    expect(store.nodeMap().has('shared-1')).toBe(true); // still required by B
    expect(store.nodeMap().has('only-b')).toBe(true); // B's exclusive reveal, untouched
    expect(store.expandedNodeIds().has('A')).toBe(false);
    expect(store.expandedNodeIds().has('B')).toBe(true);
    // Flat reveal set is recomputed from remaining provenance (B's reveals only).
    expect([...store.externalRevealedNodeIds()].sort()).toEqual(['only-b', 'shared-1']);

    // Now collapse B: nothing else needs shared-1 / only-b → both removed.
    store.collapseNodeExternal('B');
    expect(store.nodeMap().has('shared-1')).toBe(false);
    expect(store.nodeMap().has('only-b')).toBe(false);
    expect(store.expandedNodeIds().size).toBe(0);
    expect(store.externalRevealedNodeIds().size).toBe(0);
  });
});
