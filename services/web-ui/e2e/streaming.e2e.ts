import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — live alarm state (now the unified /alarms view).
 *   AC 13 : after a replayed scenario that produces at least one alarm transition, the alarms view
 *           shows the updated lifecycle state (the Streaming table + Stats alarm-lifecycle merged
 *           into ONE timestamp-sorted Alarms table — Part 3 redesign).
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The Alarms view reads Alarm Manager `/alarms` + Correlation Engine `/incidents` + `/stats`
 *     (P3 services). All are contract-mocked in `real` mode (and served by the in-app interceptor in
 *     `mock` mode). The table formatting, severity colour-coding, RCA highlight and lifecycle
 *     rendering are real app code in both modes.
 *   - The former `/streaming` route now redirects to `/alarms`, so deep links still land.
 */

test.describe('Unified Alarms view — live alarm state [AC 13]', () => {
  test('AC 13 — the Alarms view ingests alarms and renders their lifecycle state from the Alarm Manager', async ({
    page,
  }) => {
    await page.goto('/alarms');
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();

    // KPI header strip present (auto-correlation / reduction / RCA / incidents / processed).
    await expect(page.getByTestId('kpi-autocorr')).toBeVisible();
    await expect(page.getByTestId('kpi-processed')).toBeVisible();

    // Alarm rows render with a lifecycle state (sourced from Alarm Manager /alarms).
    const rows = page.getByTestId('alarm-row');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const stateCell = page.getByTestId('lifecycle-state').first();
    await expect(stateCell).toBeVisible();
    await expect(stateCell).toHaveText(/open|in-progress|correlated|cleared|reverted-open/);

    // Timestamp is the FIRST column and rendered in the full absolute format.
    const firstRaisedAt = page.getByTestId('alarm-raised-at').first();
    await expect(firstRaisedAt).toBeVisible();
    await expect(firstRaisedAt).toHaveText(/\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}\.\d{3}/);

    // Each row carries a severity pill (X.733 colour-coded scan-by-severity).
    await expect(page.getByTestId('alarm-severity').first()).toBeVisible();

    // The legacy /streaming deep link still lands (redirect to /alarms).
    await page.goto('/streaming');
    await expect(page).toHaveURL(/\/alarms/);
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();
  });

  test('AC 13 — the root-cause row is highlighted and links to the incident; children expand', async ({
    page,
  }) => {
    await page.goto('/alarms');

    // The root-cause alarm row is highlighted and carries a clickable incident icon/link.
    const rcaRow = page.locator('[data-testid="alarm-row"][data-role="root-cause"]').first();
    await expect(rcaRow).toBeVisible();
    await expect(rcaRow.getByTestId('rca-badge')).toBeVisible();
    const incidentLink = rcaRow.getByTestId('alarm-incident-link');
    await expect(incidentLink).toBeVisible();

    // Expanding the RCA row reveals its nested correlated child alarms.
    const expander = rcaRow.getByTestId('alarm-expand');
    if (await expander.count()) {
      await expander.click();
      await expect(page.locator('[data-testid="alarm-row"][data-role="child"]').first()).toBeVisible();
    }

    // The incident icon navigates to the incident detail.
    await incidentLink.click();
    await expect(page).toHaveURL(/\/incidents\//);
  });
});
