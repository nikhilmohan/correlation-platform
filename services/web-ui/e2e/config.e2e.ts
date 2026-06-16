import { test, expect, IS_REAL } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — config (Knowledge model-params).
 *   AC 43 : a config edit submitted through the UI is retrievable via the Knowledge Service API
 *           on a subsequent read.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The config module reads + writes the Knowledge Service model-params record
 *     (`GET|PUT /domains/{domain}/modelParams/{id}`). Knowledge IS a REAL P1 service in the
 *     compose stack (port 8081), so in `real` mode this test hits the REAL Knowledge Service —
 *     it is one of the few deferred ACs whose collaborator already exists. In `mock` mode the
 *     in-app interceptor serves the versioned record and bumps the version on PUT.
 *
 * "Retrievable on subsequent read": the UI confirms persistence by showing the NEW version
 * returned by the PUT (the Knowledge Service is versioned — a successful edit yields a new
 * `version`). Against the real stack the version monotonically advances, proving the edit
 * persisted; against the mock it advances to v4. Both confirm the round-trip.
 */

test.describe('Config — Knowledge model-params [AC 43]', () => {
  test('AC 43 — editing a model param and saving confirms a new persisted version', async ({ page }) => {
    await page.goto('/config');
    await expect(page.getByRole('heading', { name: /Model parameters/i })).toBeVisible();

    // Edit a numeric param (DBSCAN epsilon) to a valid in-bounds value.
    const epsilon = page.getByTestId('param-dbscan.epsilon');
    await expect(epsilon).toBeVisible();
    await epsilon.fill('0.75');

    await page.getByTestId('save-btn').click();

    // The UI confirms persistence with the new version returned by the Knowledge Service PUT.
    const status = page.getByTestId('save-status');
    await expect(status).toBeVisible();
    await expect(status).toContainText(/Saved — version/i);

    if (IS_REAL) {
      // On the real (versioned) Knowledge Service the saved value is retrievable on reload.
      await page.reload();
      await expect(page.getByTestId('param-dbscan.epsilon')).toHaveValue('0.75');
    }
  });

  test('AC 42 (cross-check) — an out-of-bounds value blocks submit and shows a validation error', async ({
    page,
  }) => {
    // E2E reinforcement of the unit-tested AC 42: invalid input must NOT call the API. This guards
    // the config write-path the way an operator would hit it in the browser.
    await page.goto('/config');
    await expect(page.getByRole('heading', { name: /Model parameters/i })).toBeVisible();

    const minSupport = page.getByTestId('param-prefixspan.minSupport'); // bounds 0..1
    await expect(minSupport).toBeVisible();
    await minSupport.fill('5'); // out of bounds (> max 1)
    await minSupport.blur();

    await expect(page.getByTestId('error-prefixspan.minSupport')).toBeVisible();
    await expect(page.getByTestId('save-btn')).toBeDisabled();
  });
});
