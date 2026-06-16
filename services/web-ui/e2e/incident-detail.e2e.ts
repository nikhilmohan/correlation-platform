import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — incident-detail drill-down.
 *   AC 17 : navigating DIRECTLY to an incident-detail URL (`/incidents/<id>`) after a replayed
 *           fiber-cut scenario renders the root-cause alarm and at least one child alarm matching
 *           the Correlation Engine's incident record.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The page reads Correlation Engine `/incidents/{id}` (incident record) and Alarm Manager
 *     `/alarms/{id}` (per-member detail) — both P3 services, contract-mocked in `real` mode and
 *     served by the in-app interceptor in `mock` mode. The fiber-cut incident used here
 *     (INC-12: root LOS = a-3, children a-7/a-8) is shaped to the frozen CE/AM OpenAPI.
 *   - This is a DEEP-LINK test: the page must load without prior navigation (also covers the
 *     deep-linkable-routes requirement, spec AC 23).
 */

test.describe('Incident-detail drill-down [AC 17]', () => {
  test('AC 17 — deep link to /incidents/<id> renders the root-cause alarm and >=1 child alarm', async ({
    page,
  }) => {
    // Deep-link straight to the replayed fiber-cut incident (no prior navigation).
    await page.goto('/incidents/INC-12');

    await expect(page.getByRole('heading', { name: /Incident INC-12/i })).toBeVisible();

    // Root-cause alarm rendered (CE rootCauseAlarmId joined to AM alarm detail).
    const rootCause = page.getByTestId('root-cause');
    await expect(rootCause).toBeVisible();
    await expect(rootCause).toContainText(/a-3/);

    // At least one child alarm rendered, matching the incident record's childAlarmIds.
    const children = page.getByTestId('child-alarm');
    await expect(children.first()).toBeVisible();
    expect(await children.count()).toBeGreaterThanOrEqual(1);

    // The incident carries its trail as a shareable deep link into the topology view.
    await expect(page.getByTestId('trail-link')).toBeVisible();
  });
});
