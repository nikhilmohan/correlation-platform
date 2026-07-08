import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — noise-filter effectiveness (now the graphical /noise view).
 *   AC 20 : after a replayed P2 learning scenario, the noise view renders at least one run with a
 *           non-zero `alarmsIn` count sourced from the Noise Filter run-stats API. The dense table
 *           was replaced (Part 4) with a graphical view — an aggregate headline + per-run bars.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The noise view reads the Noise Filter run-stats API (`/api/v1/run-stats`) — a P2 service.
 *     Contract-mocked in `real` mode (shaped to the frozen NF OpenAPI: RunStatsRow with
 *     alarmsIn/clustersFormed/…) and served by the in-app interceptor in `mock` mode.
 */

test.describe('Graphical Noise view [AC 20]', () => {
  test('AC 20 — the Noise view renders >=1 run with non-zero alarmsIn + a graphical breakdown', async ({
    page,
  }) => {
    await page.goto('/noise');
    await expect(page.getByRole('heading', { name: /Noise filter/i })).toBeVisible();

    // Prominent aggregate headline (total in -> kept -> dropped -> overall reduction).
    await expect(page.getByTestId('noise-aggregate')).toBeVisible();
    const aggIn = (await page.getByTestId('agg-in').innerText()).trim();
    expect(Number(aggIn.replace(/\D/g, ''))).toBeGreaterThan(0);

    // Kept-vs-dropped proportion bar + the noise/storm gauges are rendered (CSS/SVG, no chart lib).
    await expect(page.getByTestId('agg-prop-bar')).toBeVisible();
    await expect(page.getByTestId('gauge-noise')).toBeVisible();
    await expect(page.getByTestId('gauge-storm')).toBeVisible();

    // Per-run breakdown: >=1 run with non-zero alarmsIn (AC 20) + a storm-reduction ratio.
    const rows = page.getByTestId('run-row');
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThanOrEqual(1);
    const alarmsInText = (await page.getByTestId('run-alarmsIn').first().innerText()).trim();
    expect(Number(alarmsInText.replace(/\D/g, ''))).toBeGreaterThan(0);
    await expect(page.getByTestId('run-storm').first()).toContainText(/: 1/);
  });
});
