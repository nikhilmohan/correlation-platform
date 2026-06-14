import { test, expect } from './support/fixtures';

/**
 * Deferred E2E acceptance criterion — pattern review & XAI.
 *   AC 39 : with a replayed scenario, the operator can approve a pattern, and the Pattern Manager
 *           reflects the `approved` lifecycle state on a subsequent read.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - Reads + approval-intent go to the Pattern Manager (`GET /patterns`, `POST
 *     /patterns/{id}/approve`) — a P2/P3 service, contract-mocked in `real` mode (shaped to the
 *     frozen PM OpenAPI; the mock persists the approval so a subsequent read returns `approved`)
 *     and served by the in-app interceptor in `mock` mode.
 *   - The UI never emits Kafka — it posts the approval-intent; the Pattern Manager owns the
 *     lifecycle transition + `patterns.approved` emission.
 */

test.describe('Pattern review & XAI [AC 39]', () => {
  test('AC 39 — operator approves a draft pattern; it reads back as approved from the Pattern Manager', async ({
    page,
  }) => {
    await page.goto('/patterns');
    await expect(page.getByRole('heading', { name: /^Patterns$/i })).toBeVisible();

    // Discovered (draft) tab is the default; expand the first pattern to reveal its XAI + actions.
    const firstPattern = page.getByTestId('pattern-row').first();
    await expect(firstPattern).toBeVisible();
    await firstPattern.getByTestId('pattern-expand').click();
    await expect(firstPattern.getByTestId('pattern-xai')).toBeVisible();

    // Approve → approval-intent POST to the Pattern Manager.
    await firstPattern.getByTestId('approve-btn').click();

    // Lifecycle reflects approved (either in place, or on the Active/approved tab after re-read).
    await page.getByTestId('tab-approved').click();
    const approvedRows = page.getByTestId('pattern-row');
    await expect(approvedRows.first()).toBeVisible();
    await expect(approvedRows.first().getByTestId('pattern-lifecycle')).toHaveText(/approved/i);
  });
});
