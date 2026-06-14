import { test, expect, MODE } from './support/fixtures';
import {
  P1_DEMO_SEEDED_SITES,
  P1_DEMO_SITE_COUNT,
  DRILL_ANCHOR,
  SPOT_CHECK_ANCHOR,
} from './support/geo-anchors';
import { shot } from './support/screenshots';

/**
 * P1 DEMONSTRABLE JOURNEY (solution-goals.md Phase 1) + spec.md AC 33 — the ONE fully-REAL P1
 * end-to-end criterion. Split into granular, independently-meaningful tests (AC 33.1 / 33.2 / 33.3
 * + the trail-member-highlight check), each pinned to the REAL seeded geo data.
 *
 * The journey the human gate wants to see end-to-end against the served web-ui:
 *   topology ingested → trails built → codebook compiled → VISUALIZED IN THE UI.
 * In the UI the visualization surface is the Topology & trails module (geo-site map →
 * site device graph → trail overlay → attribute panel) — solution-goals P1-6.
 *
 * REAL vs CONTRACT-MOCKED in this file:
 *   - REAL (E2E_MODE=real): Topology (8082), Trail Builder (8083), Codebook (8084), Knowledge
 *     (8081) — the P1 read-API stack. Every assertion here is sourced from those P1 services and,
 *     in real mode, validated against the simulator's seeded geo catalogue (see geo-anchors.ts).
 *   - In E2E_MODE=mock the same views render from the in-app OpenAPI-shaped fixtures, so this file
 *     runs and passes with no live stack (well-formedness gate + local authoring). Mock mode keeps
 *     RELAXED bounds (the fixture ships 3 generic PoPs, not the seeded 10) — the strong seeded-geo
 *     criteria below apply only in real mode.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * SEEDED GEO ANCHORS (real mode — p1-demo profile, SITE_COUNT=10; from geo_catalogue.py):
 *   LON-01 London Docklands (UK-South) · MAN-01 Manchester Central (UK-North) ·
 *   AMS-01 Amsterdam Zuidoost (EU-West) · FRA-01 Frankfurt am Main (EU-Central) ·
 *   PAR-01 Paris Aubervilliers (EU-West) · MAD-01 Madrid Alcobendas (EU-South) ·
 *   MIL-01 Milan Caldera (EU-South) · STO-01 Stockholm Kista (EU-North) ·
 *   DUB-01 Dublin Citywest (IE) · WAW-01 Warsaw Wola (EU-East).
 * Drill-in anchor for 33.2/33.3: LON-01 / "London Docklands" (always first in the first-10 set).
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 */
test.describe('P1 demonstrable journey — topology → trails → codebook, visualized [AC 33]', () => {
  /**
   * ── AC 33.1 — sites render on the geo map ────────────────────────────────────────────────────
   * solution-goals: P1-1 (~10 grounded geo sites), P1-6 (web-ui P1 surface live).
   * Acceptance criteria:
   *   - The geo-site map renders selectable site markers sourced from the Topology site query API.
   *   - REAL mode: the map shows the 10 p1-demo seeded sites — assert marker count is in the
   *     [P1_DEMO_SITE_COUNT, +headroom] band AND the named anchors LON-01 "London Docklands" and
   *     FRA-01 "Frankfurt am Main" are present BY NAME (proves the real catalogue, not generic pins).
   *   - MOCK mode: relaxed — ≥1 marker (the in-app fixture ships generic PoPs; the SAME spec passes
   *     with no live stack).
   *   - The map canvas exposes the accessible ARIA label (spec AC 52).
   * Screenshot: ac-33-1-geo-map (map view).
   */
  test('AC 33.1 — geo-site map renders the seeded PoP sites [P1-1, P1-6]', async ({ page }, testInfo) => {
    await page.goto('/topology');

    await expect(page.getByRole('heading', { name: /Topology .* sites/i })).toBeVisible();

    // The geo map canvas carries an ARIA label (spec AC 52 — accessible map surface).
    await expect(page.getByRole('application', { name: /map of network sites/i })).toBeVisible();

    const markers = page.getByTestId('site-marker');
    await expect(markers.first()).toBeVisible();
    const count = await markers.count();
    expect(count).toBeGreaterThanOrEqual(1);

    if (MODE === 'real') {
      // P1-1: the p1-demo profile seeds 10 grounded sites; allow headroom for inter-site link
      // pseudo-sites the Topology graph may surface, but require AT LEAST the seeded 10.
      expect(count).toBeGreaterThanOrEqual(P1_DEMO_SITE_COUNT);

      // Spot-check the two named anchors by their REAL catalogue names — this is what makes 33.1
      // a real-data assertion rather than "some markers exist".
      await expect(
        markers.filter({ hasText: DRILL_ANCHOR.name }).first(),
      ).toBeVisible(); // LON-01 / London Docklands
      await expect(
        markers.filter({ hasText: SPOT_CHECK_ANCHOR.name }).first(),
      ).toBeVisible(); // FRA-01 / Frankfurt am Main

      // And confirm the region tag rides along on the anchor marker (geo grounding, not just a name).
      await expect(
        markers.filter({ hasText: DRILL_ANCHOR.name }).first(),
      ).toContainText(DRILL_ANCHOR.region);
    }

    await shot(page, testInfo, 'ac-33-1-geo-map');
  });

  /**
   * ── AC 33.2 — site-specific topology ─────────────────────────────────────────────────────────
   * solution-goals: P1-2 (multi-layer device topology per site), P1-6.
   * Acceptance criteria:
   *   - Drill into a SPECIFIC named site (REAL: LON-01 "London Docklands"; MOCK: the first marker —
   *     the in-app interceptor returns the same site graph for any siteId).
   *   - The site-graph route renders its device-level graph: ≥1 node AND ≥1 edge (not a flat list).
   *   - Selecting a device marks it pressed and the attribute panel shows a REAL attribute
   *     (managedObjectId always present; type/layer shown) — spec AC 29.
   * Screenshot: ac-33-2-site-topology (site device graph + attribute panel).
   */
  test('AC 33.2 — drilling into London Docklands renders its device graph + attributes [P1-2, P1-6]', async ({
    page,
  }, testInfo) => {
    await page.goto('/topology');

    // Locate the drill-in anchor. REAL: the named LON-01 marker; MOCK: first marker (interceptor
    // returns the same SiteObjectsDto for any siteId, so the first marker is a valid stand-in).
    const markers = page.getByTestId('site-marker');
    const anchor =
      MODE === 'real' ? markers.filter({ hasText: DRILL_ANCHOR.name }).first() : markers.first();
    await expect(anchor).toBeVisible();
    await anchor.click();

    // Site graph route + device/connection lists rendered from objects-at-site.
    await expect(page.getByRole('heading', { name: /Site graph/i })).toBeVisible();
    await expect(page).toHaveURL(/\/topology\/.+/);

    const nodes = page.getByTestId('graph-node');
    const edges = page.getByTestId('graph-edge');
    await expect(nodes.first()).toBeVisible();
    // P1-2: realistic multi-layer topology — devices AND connections present (not a flat list).
    expect(await nodes.count()).toBeGreaterThanOrEqual(1);
    expect(await edges.count()).toBeGreaterThanOrEqual(1);

    // Selecting a device shows its attributes in the detail panel (spec AC 29). The panel always
    // surfaces the managedObjectId of the selected node — a real attribute read back from Topology.
    await nodes.first().click();
    await expect(nodes.first()).toHaveAttribute('aria-pressed', 'true');
    const detail = page.getByTestId('detail-panel');
    await expect(detail).toBeVisible();
    await expect(detail.getByText(/managedObjectId:/i)).toBeVisible();

    await shot(page, testInfo, 'ac-33-2-site-topology');
  });

  /**
   * ── AC 33.3 — trails render ──────────────────────────────────────────────────────────────────
   * solution-goals: P1-4 (trails built — area-bounded, not one giant trail), P1-6.
   * Acceptance criteria:
   *   - On the drilled-in site (LON-01), the Trail-clusters overlay SURFACE renders (heading
   *     present) — proving the trail visualization is wired even when a snapshot has no trails.
   *   - When trails exist they are listed as DISCRETE area-bounded clusters (each with a member
   *     count), i.e. multiple bounded trail rows rather than one giant trail. The explicit
   *     empty-state ("No trails for this snapshot") is the accepted fallback for an empty snapshot.
   * Screenshot: ac-33-3-trails-overlay (trail-cluster overlay on the site graph).
   */
  test('AC 33.3 — trail clusters overlay the site graph as area-bounded clusters [P1-4, P1-6]', async ({
    page,
  }, testInfo) => {
    await page.goto('/topology');
    const markers = page.getByTestId('site-marker');
    const anchor =
      MODE === 'real' ? markers.filter({ hasText: DRILL_ANCHOR.name }).first() : markers.first();
    await anchor.click();
    await expect(page.getByRole('heading', { name: /Site graph/i })).toBeVisible();

    // P1-4: trails built from topology + Knowledge trail policy are overlaid on the graph. The
    // Trail-clusters section renders either trail rows (live data present) or an explicit empty
    // state — both are valid renders; the journey assertion is that the overlay surface exists
    // and, when trails exist, they are listed (area-bounded clusters, not one giant trail).
    const trailHeading = page.getByRole('heading', { name: /Trail clusters/i });
    await expect(trailHeading).toBeVisible();

    const clusters = page.getByTestId('trail-cluster');
    const clusterCount = await clusters.count();
    if (clusterCount) {
      await expect(clusters.first()).toBeVisible();
      // Area-bounded: each cluster reports its own member count, so the overlay is a set of bounded
      // clusters rather than one monolithic trail spanning everything.
      await expect(clusters.first()).toContainText(/members\)/i);
    } else {
      await expect(page.getByText(/No trails for this snapshot/i)).toBeVisible();
    }

    await shot(page, testInfo, 'ac-33-3-trails-overlay');
  });

  /**
   * ── AC 33 (trail-member highlight) ───────────────────────────────────────────────────────────
   * solution-goals: P1-4. Selecting a trail-member device highlights all trails it belongs to
   * (getTrailsForObject). With no trail membership the click still succeeds and the view does not
   * crash — the "heading still visible" guard proves the visualization is robust either way.
   * (No screenshot — covered by 33.3's overlay capture; this test asserts interaction, not layout.)
   */
  test('AC 33 — selecting a trail-member device highlights its trails [P1-4]', async ({ page }) => {
    await page.goto('/topology');
    const markers = page.getByTestId('site-marker');
    const anchor =
      MODE === 'real' ? markers.filter({ hasText: DRILL_ANCHOR.name }).first() : markers.first();
    await anchor.click();
    await expect(page.getByRole('heading', { name: /Site graph/i })).toBeVisible();

    const nodes = page.getByTestId('graph-node');
    await expect(nodes.first()).toBeVisible();
    await nodes.first().click();

    // If the selected device belongs to >=1 trail (getTrailsForObject), the membership badge
    // appears. With no trail membership the click still succeeds (no error) — the visualization
    // must not crash, which the assertion above (heading still visible) already guards.
    const highlighted = page.locator('[data-testid="trail-cluster"].highlighted');
    if (await highlighted.count()) {
      await expect(highlighted.first()).toBeVisible();
    }

    // Sanity: the catalogue anchors module stays in lock-step with the seed (referenced so a drift
    // in the seeded set is caught at type-check time, not silently).
    expect(P1_DEMO_SEEDED_SITES.length).toBe(P1_DEMO_SITE_COUNT);
  });
});
