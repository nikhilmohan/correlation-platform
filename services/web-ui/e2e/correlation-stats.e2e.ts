import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criteria — correlation stats module.
 *   AC 46 : after a replayed fiber-cut scenario, the stats module shows >=1 incident with a
 *           tagged root-cause alarm and one or more child alarms.
 *   AC 49 : after a replayed fiber-cut scenario, the alarm-lifecycle view shows >=1 alarm in
 *           `correlated` state with a non-empty incident association, from the Alarm Manager API.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - AC 46 reads Correlation Engine `/incidents` (+ `/stats`); AC 49 reads Alarm Manager
 *     `/alarms`. Both are P3 services — contract-mocked in `real` mode (shaped to the frozen
 *     CE/AM OpenAPI) and served by the in-app interceptor in `mock` mode.
 *   - Both assertions trace to solution-goals P3-2/P3-3/P3-4 (auto-correlation, RCA tagging,
 *     alarm reduction) — the stats module surfaces the live-demonstrable subset.
 */

test.describe('Correlation stats module [AC 46, AC 49]', () => {
  test('AC 46 — stats incidents tab shows >=1 incident with a tagged root cause and child alarms', async ({
    page,
  }) => {
    await page.goto('/stats');
    await expect(page.getByRole('heading', { name: /Correlation stats/i })).toBeVisible();

    // Incidents tab is the default.
    const incidents = page.getByTestId('stats-incident');
    await expect(incidents.first()).toBeVisible();
    expect(await incidents.count()).toBeGreaterThanOrEqual(1);

    // Each row carries a tagged root cause and a children list (root LOS + children for fiber-cut).
    const first = incidents.first();
    await expect(first).toContainText(/root /i);
    await expect(first).toContainText(/children:/i);
    await expect(first).not.toContainText(/children: none/i);
  });

  test('AC 49 — alarm-lifecycle view shows >=1 correlated alarm with a non-empty incident association', async ({
    page,
  }) => {
    await page.goto('/stats');
    await expect(page.getByRole('heading', { name: /Correlation stats/i })).toBeVisible();

    // Switch to the alarm-lifecycle sub-view and filter to `correlated`.
    await page.getByTestId('tab-alarms').click();
    await expect(page.getByRole('heading', { name: /Alarm lifecycle/i })).toBeVisible();
    await page.getByTestId('alarm-filter').selectOption('correlated');

    const rows = page.getByTestId('lifecycle-row');
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThanOrEqual(1);

    // First correlated row: state == correlated AND a non-empty incident association.
    const stateCell = page.getByTestId('lifecycle-state').first();
    await expect(stateCell).toHaveText(/correlated/i);
    // The incident cell renders a link (non-empty association) rather than the "—" placeholder.
    await expect(rows.first().getByRole('link')).toBeVisible();
  });
});
