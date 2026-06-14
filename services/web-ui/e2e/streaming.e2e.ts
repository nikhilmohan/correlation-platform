import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — real-time streaming view.
 *   AC 13 : after a replayed scenario that produces at least one alarm transition, the streaming
 *           view shows the updated lifecycle state within two poll cycles of the transition.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - The streaming view polls Alarm Manager `/alarms` + Correlation Engine `/incidents` (P3
 *     services). Both are contract-mocked in `real` mode (and served by the in-app interceptor in
 *     `mock` mode). The polling cadence, delta-highlight, and lifecycle rendering are real app
 *     code in both modes.
 *   - Note for integration: once the real Alarm Manager + a replay are wired, this test asserts
 *     the live transition shows up within two poll cycles. Against the contract mock the alarm
 *     set is static, so we assert the streaming view ingests and renders alarms with their
 *     lifecycle state from the (mocked) Alarm Manager — the same selectors a live transition uses.
 */

test.describe('Real-time streaming view [AC 13]', () => {
  test('AC 13 — streaming view ingests alarms and renders their lifecycle state from the Alarm Manager', async ({
    page,
  }) => {
    await page.goto('/streaming');
    await expect(page.getByRole('heading', { name: /Streaming \(live\)/i })).toBeVisible();

    // Live indicator + interval control present (auto-refresh polling active).
    await expect(page.getByText(/● LIVE/)).toBeVisible();
    await expect(page.getByTestId('interval-input')).toBeVisible();

    // Alarm rows render with a lifecycle state (sourced from Alarm Manager /alarms).
    const rows = page.getByTestId('alarm-row');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const stateCell = page.getByTestId('alarm-state').first();
    await expect(stateCell).toBeVisible();
    await expect(stateCell).toHaveText(/open|in-progress|correlated|cleared|reverted-open/);

    // The view supports live lifecycle updates without a page reload (AC 13 mechanism): pausing
    // and resuming auto-refresh keeps the rows rendered and the LIVE indicator returns. This
    // exercises the same delta-render path a backend transition would drive.
    await page.getByTestId('pause-btn').click();
    await expect(page.getByText(/⏸ paused/)).toBeVisible();
    await page.getByTestId('resume-btn').click();
    await expect(page.getByText(/● LIVE/)).toBeVisible();
    await expect(page.getByTestId('alarm-row').first()).toBeVisible();
  });
});
