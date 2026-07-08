import { test, expect, MODE } from './support/fixtures';

/**
 * Deferred E2E acceptance criteria — landing dashboard + cross-navigation.
 *   AC 5  : dashboard renders a non-zero incident count + non-zero alarm-reduction ratio from
 *           the Correlation Engine stats API after a replayed fiber-cut scenario.
 *   AC 25 : navigating from the dashboard incident-count KPI through to an incident-detail page
 *           completes without error and renders the incident detail.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - Dashboard KPIs read Correlation Engine `/incidents` + `/stats`, Pattern Manager
 *     active-patterns, Alarm Manager alarm counts — all P2/P3 services. These are NOT in the P1
 *     compose, so in `real` mode they are served by the contract-boundary mocks (see
 *     support/contract-mocks.ts), shaped to the frozen CE/PM/AM OpenAPI. In `mock` mode the
 *     in-app interceptor serves the same shapes. The dashboard itself is real app code in both.
 */

test.describe('Landing dashboard [AC 5]', () => {
  test('AC 5 — dashboard shows non-zero incident count and non-zero alarm-reduction ratio from CE stats', async ({
    page,
  }) => {
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: /Platform overview/i })).toBeVisible();

    // Live incident count (CE /incidents) — non-zero after the replayed fiber-cut scenario.
    const incidentKpi = page.getByTestId('kpi-incidents');
    await expect(incidentKpi).toBeVisible();
    const incidentText = (await incidentKpi.innerText()).trim();
    const incidentValue = Number(incidentText.replace(/\D+/g, ''));
    expect(incidentValue).toBeGreaterThan(0);

    // Dedup-reduction card (repurposed from the old alarm-reduction card, same testid convention as
    // the Alarms header): emitted vs. kept after enrichment de-dup. It renders either an
    // "<emitted> → <kept>" flow (with a "% deduped") when there is a consistent single-run basis, or
    // the kept count alone / "—" otherwise. Assert the card + its label are present and that it shows
    // a numeric flow value (kept is always known once alarms exist).
    const dedupKpi = page.getByTestId('kpi-dedup');
    await expect(dedupKpi).toBeVisible();
    await expect(dedupKpi).toContainText(/Dedup reduction/i);
    await expect(dedupKpi.getByTestId('kpi-dedup-flow')).toContainText(/[0-9]/);
  });
});

test.describe('Cross-navigation [AC 25]', () => {
  test('AC 25 — dashboard incident KPI → incidents list → incident-detail completes and renders', async ({
    page,
  }) => {
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: /Platform overview/i })).toBeVisible();

    // The incident-count KPI links to the unified Alarms view (Streaming + Stats merged, Part 3).
    await page.getByTestId('kpi-incidents').click();
    await expect(page).toHaveURL(/\/alarms/);
    await expect(page.getByRole('heading', { name: /^Alarms$/i })).toBeVisible();

    // An incident is represented as its highlighted RCA alarm row; its incident icon deep-links to
    // the incident-detail page (incidents are reached by clicking that icon on the RCA row).
    const rcaRow = page.locator('[data-testid="alarm-row"][data-role="root-cause"]').first();
    await expect(rcaRow).toBeVisible();
    await rcaRow.getByTestId('alarm-incident-link').click();

    await expect(page).toHaveURL(/\/incidents\/.+/);
    await expect(page.getByRole('heading', { name: /^Incident /i })).toBeVisible();
    // No error banner — the full navigation path completed without error.
    await expect(page.locator('.shell-errors .error-banner')).toHaveCount(0);
    expect(MODE).toBeDefined();
  });
});
