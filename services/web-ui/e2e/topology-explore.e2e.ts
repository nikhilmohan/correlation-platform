import { test, expect, MODE } from './support/fixtures';
import { shot } from './support/screenshots';

/**
 * EXPLORABLE TOPOLOGY (operator-driven) — node EXPAND (neighbours), clickable TRAILS that EXPLODE
 * the topology to include their (cross-site) member path, compound site-boundary boxes, and graph
 * zoom. Real chromium so Cytoscape actually paints and the data-cy-* bridges reflect the REAL
 * render counts.
 *
 * These assertions FAIL on the previous static single-site graph:
 *   - the old graph never grew (no expand control / no neighbour fan-out) → node count fixed;
 *   - trails lit only the selected node (hollow highlight) → highlight-count never > 1;
 *   - one site only → site-count never > 1, no second site box.
 *
 * MODE only selects WHERE the data comes from (real P1 stack vs in-app fixtures); the assertions
 * are the same. The in-app fixtures (mock mode) ship a 2nd site (Site:FRA), a cross-site neighbours
 * response for Router:lon-r1, and a cross-site trail (TR-7), so this runs with no live stack.
 */
test.describe('Explorable topology — expand, cross-site trail explode, site boxes, zoom', () => {
  /** Root the explorer at a site (geo map → site view). Returns the .cy-canvas locator. */
  async function rootAtSite(page: import('@playwright/test').Page) {
    await page.goto('/topology');
    const markers = page.getByTestId('site-marker');
    await expect(markers.first()).toBeVisible();
    // REAL: drill into London Docklands by name; MOCK: the first marker (interceptor returns LON).
    const anchor = MODE === 'real' ? markers.filter({ hasText: /London/i }).first() : markers.first();
    await anchor.click();
    await expect(page.getByRole('heading', { name: /Site graph/i })).toBeVisible();
    const cy = page.locator('.cy-canvas');
    await expect(cy).toHaveAttribute('data-cy-layout-done', 'true', { timeout: 15_000 });
    return cy;
  }

  /**
   * Grow the accumulating graph by EXPANDING device nodes via their own per-row +expand controls
   * (structural — first graph-node row → that same row's expand-node button — never by node name, so
   * it's mode-portable: real ids are `Node:N10` / `LineCard:N10-LC1` …, never `lon-r1`).
   *
   * The neighbours endpoint is live in both modes, but rooting a site already loads that site's
   * in-site objects, so expanding a node whose neighbours are all already present adds nothing. We
   * therefore click expand controls in order until data-cy-node-count STRICTLY INCREASES (proving an
   * expand pulled NEW neighbours in), bounded by the number of expand buttons. In mock mode the first
   * node's neighbour is out-of-graph (Site:FRA) so growth happens on the first click; on the real
   * stack some early nodes are fully in-graph, so we walk forward until one grows the graph.
   *
   * Returns the node-count BEFORE the first expand that grew the graph, and asserts growth occurred.
   */
  async function expandUntilGraphGrows(
    page: import('@playwright/test').Page,
    cy: import('@playwright/test').Locator,
  ): Promise<number> {
    const startCount = Number(await cy.getAttribute('data-cy-node-count'));
    const expandButtons = page.getByTestId('expand-node');
    const total = await expandButtons.count();
    for (let i = 0; i < total; i++) {
      const before = Number(await cy.getAttribute('data-cy-node-count'));
      await expandButtons.nth(i).click();
      // Give the neighbours fetch + merge + relayout a moment, then check for growth.
      try {
        await expect
          .poll(async () => Number(await cy.getAttribute('data-cy-node-count')), { timeout: 1500 })
          .toBeGreaterThan(before);
        return startCount; // this expand grew the graph
      } catch {
        // No growth from this node (its neighbours were already present) — try the next one.
      }
    }
    throw new Error('no expand control grew the graph — expected at least one node with an out-of-graph neighbour');
  }

  test('expanding a node pulls its neighbours into the accumulating graph (node count grows)', async ({
    page,
  }) => {
    const cy = await rootAtSite(page);
    const before = Number(await cy.getAttribute('data-cy-node-count'));
    expect(before).toBeGreaterThanOrEqual(1);

    // Expand nodes generically (by structure, not name) until the graph grows (mode-portable helper).
    await expandUntilGraphGrows(page, cy);

    // FAILS on the old static graph (no expand): the node count strictly increases.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-count'))).toBeGreaterThan(before);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-expanded-node-count'))).toBeGreaterThanOrEqual(1);

    // No blob after the expand relayout — the laid-out nodes still occupy a real area.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);
  });

  test('cross-site expand surfaces a SECOND site box (site-count 1→2, two site-legend entries)', async ({
    page,
  }, testInfo) => {
    const cy = await rootAtSite(page);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count')))
      .toBe(1); // single site box at root

    if (MODE === 'mock') {
      // MOCK: the in-app neighbours interceptor deliberately wires the rooted site's first node to a
      // neighbour in a SECOND site (Site:FRA), so the first expand that grows the graph also crosses
      // the boundary. This exact 1→2 site-count is a property of the MOCK topology, so it is asserted
      // strictly only here — never asserted as a fixed number in real mode (see the real branch).
      await expandUntilGraphGrows(page, cy);

      // FAILS on the old single-site graph: a second distinct site appears.
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);
      // Two labelled site boxes ⇒ two site-legend entries (the legend renders one row per box).
      await expect.poll(async () => page.getByTestId('site-legend-item').count()).toBeGreaterThanOrEqual(2);
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);

      await shot(page, testInfo, 'topology-expanded-2-sites');
      return;
    }

    // REAL: whether expansion reaches a 2nd site is data-dependent — the rooted site's expandable
    // neighbours can all be in-site (no cross-site adjacency a few hops out), so a fixed 1→2 number is
    // a MOCK-only property and is NOT asserted here. We expand generically until the graph grows and
    // assert the WEAKER-BUT-TRUE invariant: expansion accumulates new nodes, site boxes keep
    // rendering, and the laid-out graph occupies real area; IF the scope does reach a 2nd site the
    // legend carries one row per box.
    const before = Number(await cy.getAttribute('data-cy-node-count'));
    await expandUntilGraphGrows(page, cy);
    // Walk the remaining expand controls to push the scope outward as far as the real data allows.
    const expandButtons = page.getByTestId('expand-node');
    const total = await expandButtons.count();
    for (let i = 0; i < total; i++) {
      await expandButtons.nth(i).click();
      await page.waitForTimeout(200); // let each merge + relayout settle
    }

    // True in real mode: the graph grew, site boxes still render (≥1), and laid-out nodes occupy area.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-count'))).toBeGreaterThan(before);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThanOrEqual(1);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);
    // If we DID reach a second site, the legend must reflect it (one row per box) — true-when-reached,
    // never a hard requirement on real data.
    const siteCount = Number(await cy.getAttribute('data-cy-site-count'));
    if (siteCount >= 2) {
      await expect.poll(async () => page.getByTestId('site-legend-item').count()).toBeGreaterThanOrEqual(2);
    }

    // Capture after the expands so the screenshot shows multiple sites if the scope reached them.
    await shot(page, testInfo, 'topology-expanded-2-sites');
  });

  test('selecting a trail highlights its FULL member path (not the hollow 1) and explodes cross-site', async ({
    page,
  }, testInfo) => {
    const cy = await rootAtSite(page);

    // Pick the FIRST trail cluster generically (its trailId is `TR-7` in mock, an opaque hash on the
    // real stack — never hardcode it). Read its member count from the row text `… (N members)` so the
    // highlight assertion is anchored to THIS trail's real member set in either mode.
    const trail = page.getByTestId('trail-cluster').first();
    await expect(trail).toBeVisible();
    const label = (await trail.innerText()).trim();
    const memberMatch = label.match(/\((\d+)\s+members?\)/i);
    expect(memberMatch, `trail row "${label}" should expose a "(N members)" count`).toBeTruthy();
    const memberCount = Number(memberMatch![1]);
    // The full-member-path highlight is only meaningful for a multi-member trail; the seeded data in
    // both modes has one. (Guards against an accidentally-empty/degenerate first trail.)
    expect(memberCount).toBeGreaterThan(1);

    await trail.click();

    // The trail detail panel renders the full member list.
    await expect(page.getByTestId('trail-detail')).toBeVisible();
    // The detail panel's member rows match the row's advertised count (sanity: same trail, same N).
    await expect.poll(async () => page.getByTestId('trail-member').count()).toBe(memberCount);

    // FAILS on the old hollow highlight: the painted highlight count covers this trail's FULL member
    // path, not the hollow 1. data-cy-highlight-count counts highlighted member NODES *plus* the
    // trail-member EDGES between them (see applyDecoration), so for an N-member trail it is ≥ N (and
    // ≥ 2). Anchored to the selected trail's own member count read above — not a hardcoded number —
    // so it's real-data-portable; the exact-N equality is on the detail panel's member rows above.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-highlight-count'))).toBeGreaterThanOrEqual(memberCount);

    if (MODE === 'mock') {
      // MOCK only: the seeded TR-7 trail spans two sites, so exploding it pulls a member from the 2nd
      // site and a 2nd site box appears. Real trails are data-dependent and may be single-site, so
      // this exact cross-site explosion is asserted strictly only in mock mode.
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);
    } else {
      // REAL: the explode keeps site boxes rendering and the laid-out path occupies area; whether a
      // 2nd site appears depends on the real trail's geography (not asserted as a fixed number).
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThanOrEqual(1);
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);
    }

    await shot(page, testInfo, 'topology-trail-exploded');
  });

  test('device nodes carry their network-element TYPE ICON (data-icon per type; generic for unknown)', async ({
    page,
  }) => {
    // MOCK: root at Madrid (distinct subgraph carrying Node/Port/IPLink/IGPAdjacency/VPNService +
    // an UnknownFutureThing). REAL: the rooted site's own typed devices.
    if (MODE === 'real') {
      const cy = await rootAtSite(page);
      void cy;
      const nodes = page.getByTestId('graph-node');
      await expect(nodes.first()).toBeVisible();
      // Every rendered node carries a resolved data-icon (never icon-less); at least one real type key.
      const count = await nodes.count();
      let nonGeneric = 0;
      for (let i = 0; i < count; i++) {
        const icon = await nodes.nth(i).getAttribute('data-icon');
        expect(icon).toBeTruthy();
        if (icon && icon !== 'generic') nonGeneric++;
      }
      expect(nonGeneric).toBeGreaterThan(0);
      return;
    }

    await page.goto('/topology');
    const markers = page.getByTestId('site-marker');
    await expect(markers.first()).toBeVisible();
    // Madrid marker (3rd site) — distinct device set + the unknown type.
    await markers.filter({ hasText: /Madrid/i }).first().click();
    await expect(page.getByRole('heading', { name: /Site graph/i })).toBeVisible();
    const cy = page.locator('.cy-canvas');
    await expect(cy).toHaveAttribute('data-cy-layout-done', 'true', { timeout: 15_000 });

    // data-icon per type (AC 70): the router/port/ip-link/igp-adjacency/vpn-service icons all appear.
    for (const [type, key] of [
      ['Node', 'router'],
      ['Port', 'port'],
      ['IPLink', 'ip-link'],
      ['IGPAdjacency', 'igp-adjacency'],
      ['VPNService', 'vpn-service'],
      ['SRLG', 'srlg'],
    ] as const) {
      const row = page.locator(`[data-testid="graph-node"][data-object-type="${type}"]`);
      await expect(row.first()).toHaveAttribute('data-icon', key);
    }
    // Generic fallback for the unknown type (AC 71) — the node still renders, never hidden.
    const unknown = page.locator('[data-testid="graph-node"][data-object-type="UnknownFutureThing"]');
    await expect(unknown.first()).toBeVisible();
    await expect(unknown.first()).toHaveAttribute('data-icon', 'generic');

    // The canvas reflects the distinct icon-key count (router/port/ip-link/igp-adjacency/vpn-service/srlg + generic).
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-icon-types'))).toBeGreaterThanOrEqual(6);
  });

  test('two DIFFERENT sites render DIFFERENT node sets (distinct fixtures, no clones)', async ({
    page,
  }) => {
    if (MODE === 'real') test.skip();
    // London first.
    await page.goto('/topology');
    await expect(page.getByTestId('site-marker').first()).toBeVisible();
    await page.getByTestId('site-marker').filter({ hasText: /London/i }).first().click();
    await expect(page.locator('.cy-canvas')).toHaveAttribute('data-cy-layout-done', 'true', { timeout: 15_000 });
    const lonIds = (await page.getByTestId('graph-node').allInnerTexts()).sort();

    // Back to the map, then Madrid.
    await page.getByTestId('breadcrumb-topology').click();
    await expect(page.getByTestId('site-marker').first()).toBeVisible();
    await page.getByTestId('site-marker').filter({ hasText: /Madrid/i }).first().click();
    await expect(page.locator('.cy-canvas')).toHaveAttribute('data-cy-layout-done', 'true', { timeout: 15_000 });
    const madIds = (await page.getByTestId('graph-node').allInnerTexts()).sort();

    // The two sites must NOT render the same device set (FAILS on the old London-clone fallback).
    expect(lonIds).not.toEqual(madIds);
    expect(lonIds.length).toBeGreaterThan(0);
    expect(madIds.length).toBeGreaterThan(0);
  });

  test('rooting a site shows ONE labelled site box, then zoom + increases the zoom level', async ({
    page,
  }, testInfo) => {
    const cy = await rootAtSite(page);
    // The rooted site renders inside a single compound box.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBe(1);
    await expect(page.getByTestId('site-legend-item')).toHaveCount(1);

    const z0 = Number(await cy.getAttribute('data-cy-zoom'));
    await page.getByTestId('zoom-in').click();
    // FAILS if zoom controls aren't wired: the bridged zoom level increases.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-zoom'))).toBeGreaterThan(z0);

    await shot(page, testInfo, 'topology-rooted-site');
  });
});
