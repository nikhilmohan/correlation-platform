import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { TopologyStore } from './topology.store';
import { TopologyClient } from '../api/topology.client';
import { NeighborsDto, NodeDto, SiteObjectsDto } from '../api/models';
import { testProviders, flush } from '../../test-utils';

/** A SiteObjectsDto pre-seeded with `count` Router nodes (no edges) — drives a graph near the cap. */
function seedWith(count: number): SiteObjectsDto {
  return {
    siteId: 'Site:LON',
    domain: 'core-ip',
    snapshotId: 'current',
    nodeCount: count,
    edgeCount: 0,
    nodes: Array.from({ length: count }, (_, i) => ({
      managedObjectId: `seed-${i}`,
      objectType: 'Router',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: {},
    })) as NodeDto[],
    edges: [],
  };
}

function neighborsReturning(nodes: NodeDto[], edges: NeighborsDto['neighbors'][number]['via'][] = []): NeighborsDto {
  return {
    managedObjectId: 'seed-0',
    domain: 'core-ip',
    neighbors: nodes.map((node, i) => ({
      node,
      via: edges[i] ?? { edgeId: `via-${node.managedObjectId}`, from: 'seed-0', to: node.managedObjectId, relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    })),
  };
}

function configure(seedCount: number, nbr: NeighborsDto): TopologyStore {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      {
        provide: TopologyClient,
        useValue: {
          listSites: () => of({ domain: 'core-ip', snapshotId: 'current', count: 0, sites: [] }),
          objectsAtSite: () => of(seedWith(seedCount)),
          neighbors: () => of(nbr),
        },
      },
    ],
  });
  return TestBed.inject(TopologyStore);
}

describe('Explorer NODE_CAP — all-or-nothing (AC 56, 57)', () => {
  it('AC 57 — expansion that would push OVER the cap is rejected wholesale; count unchanged + capReached', async () => {
    const cap = 250; // default TOPOLOGY_NODE_CAP
    // Pre-seed to the cap, then a neighbours response with ONE unseen node.
    const oneMore = neighborsReturning([{ managedObjectId: 'over-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }]);
    const s = configure(cap, oneMore);
    s.selectSite('Site:LON');
    await flush();
    const before = s.nodeMap().size;
    expect(before).toBe(cap);

    s.expandNode('seed-0');
    await flush();

    // Nothing added (all-or-nothing) — FAILS on the old per-node partial-add (it would have stopped
    // at the cap but here the graph is already AT the cap so the one new node would overflow).
    expect(s.nodeMap().size).toBe(before);
    expect(s.capReached()).toBe(true);
  });

  it('AC 57 — a multi-node expansion that partially fits is STILL rejected wholesale (no partial add)', async () => {
    const cap = 250;
    // Two unseen below the cap so a partial add COULD fit one — but all-or-nothing rejects both.
    const two = neighborsReturning([
      { managedObjectId: 'x-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      { managedObjectId: 'x-2', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ]);
    const s = configure(cap - 1, two); // room for exactly one of the two
    s.selectSite('Site:LON');
    await flush();
    const before = s.nodeMap().size;

    s.expandNode('seed-0');
    await flush();

    // Neither was added (no partial add of x-1) — this is the crux of AC 57.
    expect(s.nodeMap().size).toBe(before);
    expect(s.nodeMap().has('x-1')).toBe(false);
    expect(s.nodeMap().has('x-2')).toBe(false);
    expect(s.capReached()).toBe(true);
  });

  it('AC 57 — an expansion that EXACTLY fills the cap is accepted', async () => {
    const cap = 250;
    const two = neighborsReturning([
      { managedObjectId: 'x-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      { managedObjectId: 'x-2', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ]);
    const s = configure(cap - 2, two); // exactly room for both
    s.selectSite('Site:LON');
    await flush();

    s.expandNode('seed-0');
    await flush();

    expect(s.nodeMap().size).toBe(cap);
    expect(s.nodeMap().has('x-1')).toBe(true);
    expect(s.nodeMap().has('x-2')).toBe(true);
    expect(s.capReached()).toBe(false);
  });

  it('AC 56 — re-expanding a fully-present node adds NO duplicates (zero new nodes never overflows)', async () => {
    // Neighbours that are all ALREADY in the seed → zero new nodes.
    const present = neighborsReturning([
      { managedObjectId: 'seed-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      { managedObjectId: 'seed-2', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ]);
    const s = configure(5, present);
    s.selectSite('Site:LON');
    await flush();
    const nodesBefore = s.nodeMap().size;
    const edgesBefore = s.edgeMap().size;

    s.expandNode('seed-0');
    await flush();

    // Node count unchanged (dedupe); the new connecting edges still merged (edges-only allowed).
    expect(s.nodeMap().size).toBe(nodesBefore);
    expect(s.edgeMap().size).toBeGreaterThanOrEqual(edgesBefore);
    expect(s.capReached()).toBe(false);
  });

  it('AC 56 — an edges-only expansion is allowed EVEN AT the cap (zero new nodes)', async () => {
    const cap = 250;
    // All neighbour nodes already present (they are seed nodes) → an edges-only merge at the cap.
    const present = neighborsReturning([
      { managedObjectId: 'seed-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ]);
    const s = configure(cap, present);
    s.selectSite('Site:LON');
    await flush();
    expect(s.nodeMap().size).toBe(cap);

    s.expandNode('seed-0');
    await flush();

    // No new nodes → never overflows; capReached stays false and the edge merged.
    expect(s.nodeMap().size).toBe(cap);
    expect(s.capReached()).toBe(false);
    expect(s.edgeMap().has('via-seed-1')).toBe(true);
  });
});
