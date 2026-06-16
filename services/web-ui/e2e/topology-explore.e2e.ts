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

  test('expanding a node pulls its neighbours into the accumulating graph (node count grows)', async ({
    page,
  }) => {
    const cy = await rootAtSite(page);
    const before = Number(await cy.getAttribute('data-cy-node-count'));
    expect(before).toBeGreaterThanOrEqual(1);

    // Expand a specific node (Router:lon-r1) via its explicit +expand control.
    const expandBtn = page.getByRole('button', { name: /Expand neighbours of .*lon-r1/i }).first();
    await expandBtn.click();

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

    // Expanding Router:lon-r1 crosses the site boundary (its neighbour is in another site).
    await page.getByRole('button', { name: /Expand neighbours of .*lon-r1/i }).first().click();

    // FAILS on the old single-site graph: a second distinct site appears.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);
    // Two labelled site boxes ⇒ two site-legend entries (the legend renders one row per box).
    await expect.poll(async () => page.getByTestId('site-legend-item').count()).toBeGreaterThanOrEqual(2);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);

    await shot(page, testInfo, 'topology-expanded-2-sites');
  });

  test('selecting a trail highlights its FULL member path (not the hollow 1) and explodes cross-site', async ({
    page,
  }, testInfo) => {
    const cy = await rootAtSite(page);

    // Click the TR-7 trail cluster (members span two sites).
    const trail = page.getByTestId('trail-cluster').filter({ hasText: 'TR-7' }).first();
    await expect(trail).toBeVisible();
    await trail.click();

    // The trail detail panel renders the full member list.
    await expect(page.getByTestId('trail-detail')).toBeVisible();

    // FAILS on the old hollow highlight: the highlight count is the full member set (> 1), not 1.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-highlight-count'))).toBeGreaterThan(1);
    // Cross-site explode → a 2nd site box appears (the trail's FRA member was pulled in).
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);

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
