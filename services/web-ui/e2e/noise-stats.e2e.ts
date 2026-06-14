import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — noise-filter run-stats view.
 *   AC 20 : after a replayed P2 learning scenario, the noise-stats view renders at least one
 *           run-stats row with a non-zero `alarmsIn` count sourced from the Noise Filter
 *           run-stats API.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The noise-stats view (the "Noise run-stats" tab of the correlation-stats module) reads the
 *     Noise Filter run-stats API (`/api/v1/run-stats`) — a P2 service. It is contract-mocked in
 *     `real` mode (shaped to the frozen NF OpenAPI: RunStatsRow with alarmsIn/clustersFormed/…)
 *     and served by the in-app interceptor in `mock` mode.
 */

test.describe('Noise-filter run-stats view [AC 20]', () => {
  test('AC 20 — noise-stats tab renders >=1 run row with non-zero alarmsIn from the Noise Filter API', async ({
    page,
  }) => {
    await page.goto('/stats');
    await expect(page.getByRole('heading', { name: /Correlation stats/i })).toBeVisible();

    // Switch to the learning sub-view (noise run-stats tab).
    await page.getByTestId('tab-noise').click();
    await expect(page.getByRole('heading', { name: /Noise-filter run-stats/i })).toBeVisible();

    const rows = page.getByTestId('run-row');
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThanOrEqual(1);

    // Non-zero alarmsIn on the first run (AC 20).
    const alarmsInText = (await page.getByTestId('run-alarmsIn').first().innerText()).trim();
    expect(Number(alarmsInText)).toBeGreaterThan(0);

    // Storm-reduction ratio (alarmsIn / clustersFormed) is derived and shown.
    await expect(page.getByTestId('run-storm').first()).toContainText(/: 1/);
  });
});
