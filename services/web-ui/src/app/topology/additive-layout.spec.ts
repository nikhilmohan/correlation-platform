import { describe, expect, it } from 'vitest';
import { SiteGraphComponent } from './site-graph.component';

/**
 * BUG 1 — ADDITIVE-MERGE position contract. A trail explode / expand / external-reveal must ADD nodes
 * AROUND the existing graph without moving any node already on the canvas, EVEN when the add crosses a
 * site boundary (single→multi-site). The previous bug re-ran a full preset recompute the moment the
 * distinct-site count went 1→2, which scattered the base tree.
 *
 * SiteGraphComponent.computeAdditivePositions is the pure crux of that guarantee: existing nodes keep
 * their EXACT prior position; only NEW nodes are placed (in a deterministic cluster offset to the right
 * of the existing extent). We test it directly so the pixel-stability is asserted without a Cytoscape
 * canvas (jsdom has no WebGL).
 */
describe('BUG 1 — computeAdditivePositions keeps existing nodes pixel-stable on an additive merge', () => {
  it('re-maps every EXISTING node to its EXACT prior position (verbatim)', () => {
    const prevPos = new Map<string, { x: number; y: number }>([
      ['Router:mad-r1', { x: 100, y: 50 }],
      ['Router:mad-r2', { x: 100, y: 200 }],
      ['Interface:mad-r1-e1', { x: 250, y: 50 }],
    ]);
    // Cross-site explode: the new trail members belong to a DIFFERENT site (Barcelona).
    const newLeafIds = ['Router:bcn-r1', 'Interface:bcn-r1-e1'];
    const siteOf = (id: string): string => (id.includes('mad') ? 'Site:MAD' : 'Site:BCN');

    const positions = SiteGraphComponent.computeAdditivePositions(prevPos, newLeafIds, siteOf);

    // Every base node is at the SAME position it had before — not moved one pixel.
    for (const [id, p] of prevPos) {
      expect(positions[id]).toEqual(p);
    }
  });

  it('places NEW nodes to the RIGHT of the existing extent so they never overlap the locked base', () => {
    const prevPos = new Map<string, { x: number; y: number }>([
      ['a', { x: 0, y: 0 }],
      ['b', { x: 300, y: 0 }], // maxX of the base = 300
      ['c', { x: 150, y: 150 }],
    ]);
    const newLeafIds = ['n1', 'n2'];
    const positions = SiteGraphComponent.computeAdditivePositions(prevPos, newLeafIds, () => 'Site:NEW');

    // The new cluster starts strictly to the right of the base maxX (300) plus the clearance gap.
    expect(positions['n1'].x).toBeGreaterThan(300);
    expect(positions['n2'].x).toBeGreaterThan(300);
  });

  it('is fully deterministic — the same inputs yield byte-identical positions', () => {
    const prev = new Map<string, { x: number; y: number }>([['x', { x: 10, y: 20 }]]);
    const ids = ['p', 'q', 'r'];
    const siteOf = (): string => 'S';
    const a = SiteGraphComponent.computeAdditivePositions(prev, ids, siteOf);
    const b = SiteGraphComponent.computeAdditivePositions(prev, ids, siteOf);
    expect(a).toEqual(b);
  });

  it('a CONTRACT (no new nodes) leaves every surviving base node at its prior position', () => {
    // After toggling full path off, the remaining graph is exactly the base set — all "existing", no new.
    const prevPos = new Map<string, { x: number; y: number }>([
      ['Router:mad-r1', { x: 100, y: 50 }],
      ['Router:mad-r2', { x: 100, y: 200 }],
    ]);
    const positions = SiteGraphComponent.computeAdditivePositions(prevPos, [], () => 'Site:MAD');
    expect(positions).toEqual({
      'Router:mad-r1': { x: 100, y: 50 },
      'Router:mad-r2': { x: 100, y: 200 },
    });
  });

  it('groups new nodes by site box so a multi-site explode reads as separate clusters', () => {
    const prevPos = new Map<string, { x: number; y: number }>([['base', { x: 0, y: 0 }]]);
    const newLeafIds = ['s1-a', 's1-b', 's2-a'];
    const siteOf = (id: string): string => (id.startsWith('s1') ? 'Site:1' : 'Site:2');
    const positions = SiteGraphComponent.computeAdditivePositions(prevPos, newLeafIds, siteOf);

    // The second site's cluster is offset further right than the first site's cluster.
    const site1MaxX = Math.max(positions['s1-a'].x, positions['s1-b'].x);
    expect(positions['s2-a'].x).toBeGreaterThan(site1MaxX);
  });
});
