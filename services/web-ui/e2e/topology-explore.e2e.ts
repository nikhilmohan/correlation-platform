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
