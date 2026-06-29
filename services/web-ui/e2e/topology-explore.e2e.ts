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
 * MODE selects WHERE the data comes from (real P1 stack vs in-app fixtures). The structural
 * assertions (graph grows on expand, full-path trail highlight, node spread) hold in BOTH modes and
 * are driven from the RENDERED DOM (generic "+" controls / first trail cluster, counts parsed from
 * the element's own text) — no literal mock ids. ONLY the genuinely mock-specific cross-site numbers
 * are MODE-gated: the in-app fixtures (mock mode) ship a 2nd site (Site:FRA), a cross-site neighbours
 * response, and a cross-site trail, so site-count 1→2 is asserted in mock only — real P1 London is
 * single-site and never asserts site-count>1.
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

  /** UX redesign: the Devices/Connections lists are collapsed by default behind the "List view"
   *  disclosure. Tests that assert against the accessible list rows open it first (the rows stay in
   *  the DOM either way; opening makes them visible so toBeVisible()/clicks resolve). Idempotent. */
  async function openListView(page: import('@playwright/test').Page) {
    const toggle = page.getByTestId('list-view-toggle');
    await expect(toggle).toBeVisible();
    if ((await toggle.getAttribute('aria-expanded')) !== 'true') {
      await toggle.click();
    }
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  }

  /**
   * Mode-portable expand driver. Rooting a site (canvas-centric redesign) preloads the site's
   * in-site objects, so the FIRST on-canvas "+" node's neighbours may already be present and clicking
   * it grows nothing (a real finding: only an out-of-graph-neighbour node grows the count). So we
   * click the on-canvas "+" (data-testid="expand-node") controls IN ORDER until data-cy-node-count
   * strictly grows — bounded by the number of controls. Picks the expand target from the RENDERED DOM
   * (no literal node id), so it works in BOTH mock and real. Returns the before/after node counts.
   * Throws if no control grows the graph (which would be a genuine product regression, not a flake).
   */
  async function expandUntilGraphGrows(
    page: import('@playwright/test').Page,
    cy: import('@playwright/test').Locator,
  ): Promise<{ before: number; after: number }> {
    const before = Number(await cy.getAttribute('data-cy-node-count'));
    const controls = page.locator('.cy-expand-layer [data-testid="expand-node"]');
    const total = await controls.count();
    expect(total).toBeGreaterThanOrEqual(1);
    for (let i = 0; i < total; i++) {
      // Re-resolve the control each iteration: a successful expand relayouts and re-renders the "+"
      // overlay, invalidating earlier handles. Some clicks add only already-present neighbours
      // (no growth) — that's expected; keep going until one strictly grows the graph.
      const control = page.locator('.cy-expand-layer [data-testid="expand-node"]').nth(i);
      if (!(await control.isVisible().catch(() => false))) {
        continue;
      }
      await control.click();
      // Short per-control budget: a node whose neighbours are already present grows nothing and we
      // must move on quickly (the rooted site preloads in-site objects, so most "+" don't grow); a
      // real out-of-graph neighbour relayouts fast. Keeps the bounded loop well under the test timeout
      // even under multi-worker load against the live stack.
      let grew = false;
      try {
        await expect
          .poll(async () => Number(await cy.getAttribute('data-cy-node-count')), { timeout: 2_000 })
          .toBeGreaterThan(before);
        grew = true;
      } catch {
        grew = false;
      }
      if (grew) {
        const after = Number(await cy.getAttribute('data-cy-node-count'));
        return { before, after };
      }
    }
    throw new Error(
      `No expand-node control grew the graph (started at ${before} nodes, tried ${total} controls)`,
    );
  }

  test('expanding a node pulls its neighbours into the accumulating graph (node count grows)', async ({
    page,
  }) => {
    // The mode-portable expand driver may click several "+" controls (the rooted site preloads its
    // in-site objects, so most "+" add only already-present neighbours) before one grows the graph;
    // against the live stack under multi-worker load that bounded scan needs headroom over the 30s
    // default. Test-config only — no product change.
    test.setTimeout(90_000);
    const cy = await rootAtSite(page);

    // Mode-portable: pick the expand target from the RENDERED on-canvas "+" controls (not a literal
    // mock id like lon-r1), clicking in order until the graph strictly grows. Holds in mock AND real.
    const { before, after } = await expandUntilGraphGrows(page, cy);

    // FAILS on the old static graph (no expand): the node count strictly increases.
    expect(after).toBeGreaterThan(before);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-expanded-node-count'))).toBeGreaterThanOrEqual(1);

    // No blob after the expand relayout — the laid-out nodes still occupy a real area.
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);
  });

  test('cross-site expand surfaces a SECOND site box (site-count 1→2, two site-legend entries)', async ({
    page,
  }, testInfo) => {
    // Same bounded expand scan as above — give the live-stack/multi-worker run headroom. Config only.
    test.setTimeout(90_000);
    const cy = await rootAtSite(page);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count')))
      .toBe(1); // single site box at root

    // Mode-portable: drive expansion generically from the rendered "+" controls (no literal node id).
    const { before, after } = await expandUntilGraphGrows(page, cy);
    expect(after).toBeGreaterThan(before);
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);

    // MODE GATE: the strict cross-site explode (site-count 1→2 + ≥2 legend entries) is a property of
    // the MOCK topology only — the in-app fixtures inject a synthetic cross-site neighbour. Real P1
    // London (Site:LON-01) is single-site, so its neighbours stay in-site; we assert the true weaker
    // invariant there (≥1 site box) and NEVER assert site-count>1 in real mode.
    if (MODE === 'mock') {
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);
      // Two labelled site boxes ⇒ two site-legend entries (the legend renders one row per box).
      await expect.poll(async () => page.getByTestId('site-legend-item').count()).toBeGreaterThanOrEqual(2);
    } else {
      // Real single-site data: expand still exercised + the rooted site box stays present (≥1).
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThanOrEqual(1);
      await expect.poll(async () => page.getByTestId('site-legend-item').count()).toBeGreaterThanOrEqual(1);
    }

    await shot(page, testInfo, 'topology-expanded-2-sites');
  });

  test('selecting a trail highlights its FULL member path (not the hollow 1) and explodes cross-site', async ({
    page,
  }, testInfo) => {
    const cy = await rootAtSite(page);

    // #291 redesign: trail clusters live inside the floating trail SELECTOR dropdown — open it
    // before reaching the per-trail rows (courtesy only; this test's cross-site assertions remain
    // a known #285 failure on real single-site P1 data and are unchanged).
    await page.getByTestId('trail-selector').click();
    await expect(page.getByTestId('trail-menu')).toBeVisible();

    // Mode-portable: select the FIRST rendered trail cluster (no literal mock id like TR-7). Some
    // snapshots may have no trails — skip cleanly rather than hardcode a trail that doesn't exist.
    const trail = page.getByTestId('trail-cluster').first();
    const trailCount = await page.getByTestId('trail-cluster').count();
    if (trailCount === 0) {
      test.skip(true, 'No trails for the rooted snapshot — nothing to explode.');
      return;
    }
    await expect(trail).toBeVisible();

    // Parse the trail's OWN member count from its row text — e.g. "trail-07ee… (4 members)".
    const trailText = (await trail.innerText()) ?? '';
    const memberMatch = trailText.match(/\((\d+)\s+members?\)/);
    expect(memberMatch, `trail-cluster text "${trailText}" must report its member count`).not.toBeNull();
    const memberCount = Number(memberMatch![1]);

    await trail.click();

    // The trail detail panel renders the full member list.
    await expect(page.getByTestId('trail-detail')).toBeVisible();

    // CHANGE 2c: a plain trail SELECT is highlight-only (in-site portion). The cross-site EXPLODE is
    // an explicit opt-in — click "Show full path across sites" to pull the off-site members + their
    // neighbours into the graph so the full path renders + highlights.
    await page.getByTestId('explode-trail').click();

    // Non-hollow full-path highlight: after the explode the trail's full member set is in the graph
    // and highlighted (member nodes + the edges between them), so the painted highlight count reflects
    // the trail's OWN member count — NOT the old hollow single node. Holds in mock AND real.
    await expect
      .poll(async () => Number(await cy.getAttribute('data-cy-highlight-count')))
      .toBeGreaterThanOrEqual(memberCount);
    if (memberCount > 1) {
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-highlight-count'))).toBeGreaterThan(1);
    }
    await expect.poll(async () => Number(await cy.getAttribute('data-cy-node-spread'))).toBeGreaterThan(40);

    // MODE GATE: the cross-site EXPLODE (a 2nd site box appears) is a MOCK-topology property — the
    // mock ships TR-7 with a member in a second site (Site:FRA). Real trail geography is data-dependent
    // and P1 trails are area/site-bounded, so we NEVER assert site-count>1 in real mode.
    if (MODE === 'mock') {
      await expect.poll(async () => Number(await cy.getAttribute('data-cy-site-count'))).toBeGreaterThan(1);
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
      await openListView(page);
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
    await openListView(page);

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
    await openListView(page);
    const lonIds = (await page.getByTestId('graph-node').allInnerTexts()).sort();

    // Back to the map, then Madrid.
    await page.getByTestId('breadcrumb-topology').click();
    await expect(page.getByTestId('site-marker').first()).toBeVisible();
    await page.getByTestId('site-marker').filter({ hasText: /Madrid/i }).first().click();
    await expect(page.locator('.cy-canvas')).toHaveAttribute('data-cy-layout-done', 'true', { timeout: 15_000 });
    await openListView(page);
    const madIds = (await page.getByTestId('graph-node').allInnerTexts()).sort();

    // The two sites must NOT render the same device set (FAILS on the old London-clone fallback).
    expect(lonIds).not.toEqual(madIds);
    expect(lonIds.length).toBeGreaterThan(0);
    expect(madIds.length).toBeGreaterThan(0);
  });

  test('UX redesign: always-visible on-canvas "+" affordances render per device node + track the canvas', async ({
    page,
  }) => {
    const cy = await rootAtSite(page);
    const nodeCount = Number(await cy.getAttribute('data-cy-node-count'));
    expect(nodeCount).toBeGreaterThanOrEqual(1);

    // The on-canvas "+" overlay buttons render (one per rendered device node) and are VISIBLE on the
    // canvas without opening any list — the canvas IS the interface. (data-testid="expand-node".)
    const plus = page.locator('.cy-expand-layer [data-testid="expand-node"]');
    await expect.poll(async () => plus.count()).toBe(nodeCount);
    await expect(plus.first()).toBeVisible();
    // They carry the accessible "Expand neighbours of <name>" label the assistive tech + e2e use.
    await expect(plus.first()).toHaveAttribute('aria-label', /^Expand neighbours of /);

    // They track the node on zoom: capture a "+" position, zoom in, expect it to move.
    const before = await plus.first().boundingBox();
    await page.getByTestId('zoom-in').click();
    await expect
      .poll(async () => {
        const now = await plus.first().boundingBox();
        return now && before ? Math.abs(now.x - before.x) + Math.abs(now.y - before.y) : 0;
      })
      .toBeGreaterThan(0);
  });

  test('UX redesign: Devices/Connections lists are collapsed by default behind the "List view" disclosure', async ({
    page,
  }) => {
    await rootAtSite(page);
    const toggle = page.getByTestId('list-view-toggle');
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // Collapsed: the device rows are present in the DOM (a11y + test bridge) but NOT visible.
    await expect(page.getByTestId('graph-node').first()).toBeHidden();

    // Disclose → rows become visible; collapse → hidden again, but never removed from the DOM.
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('graph-node').first()).toBeVisible();
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('graph-node').first()).toBeHidden();
    expect(await page.getByTestId('graph-node').count()).toBeGreaterThanOrEqual(1);
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
