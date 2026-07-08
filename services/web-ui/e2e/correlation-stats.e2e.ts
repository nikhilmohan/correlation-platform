import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criteria — correlation stats (now surfaced on the unified /alarms view).
 *   AC 46 : after a replayed fiber-cut scenario, an incident with a tagged root-cause alarm and one
 *           or more child alarms is shown. In the redesign (Part 3) an incident IS its RCA alarm row
 *           with its correlated children grouped beneath it (no separate incidents list).
 *   AC 49 : after a replayed fiber-cut scenario, the alarms view shows >=1 alarm in `correlated`
 *           state with a non-empty incident association, from the Alarm Manager API.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - AC 46/49 read Correlation Engine `/incidents` (+ `/stats`) and Alarm Manager `/alarms` — P3
 *     services, contract-mocked in `real` mode and served by the in-app interceptor in `mock` mode.
 *   - The former `/stats` route now redirects to `/alarms`.
 */

test.describe('Correlation on the unified Alarms view [AC 46, AC 49]', () => {
  test('AC 46 — an incident is shown as a highlighted RCA row with a tagged root cause + children', async ({
    page,
  }) => {
    await page.goto('/alarms');
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();

    // The root-cause alarm row is highlighted (RCA badge) and carries a clickable incident link.
    const rcaRow = page.locator('[data-testid="alarm-row"][data-role="root-cause"]').first();
    await expect(rcaRow).toBeVisible();
    await expect(rcaRow.getByTestId('rca-badge')).toBeVisible();
    await expect(rcaRow.getByTestId('alarm-incident-link')).toBeVisible();

    // Its correlated child alarms are grouped beneath it (expandable).
    const expander = rcaRow.getByTestId('alarm-expand');
    await expect(expander).toBeVisible();
    await expander.click();
    await expect(page.locator('[data-testid="alarm-row"][data-role="child"]').first()).toBeVisible();
  });

  test('AC 49 — the Alarms view shows >=1 correlated alarm with a non-empty incident association', async ({
    page,
  }) => {
    await page.goto('/alarms');
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();

    // Filter to `correlated`.
    await page.getByTestId('alarm-filter').selectOption('correlated');

    const rows = page.getByTestId('alarm-row');
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThanOrEqual(1);

    // First correlated row: state == correlated AND a non-empty incident association (the link).
    const stateCell = page.getByTestId('lifecycle-state').first();
    await expect(stateCell).toHaveText(/correlated/i);
    await expect(page.getByTestId('alarm-incident-link').first()).toBeVisible();
  });

  test('legacy /stats deep link redirects to the unified /alarms view', async ({ page }) => {
    await page.goto('/stats');
    await expect(page).toHaveURL(/\/alarms/);
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();
  });
});
