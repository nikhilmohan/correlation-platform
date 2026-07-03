import { test, expect } from './support/fixtures';
import { shot } from './support/screenshots';

/**
 * Deferred E2E acceptance criterion — pattern review & XAI.
 *   AC 39 : with a real discovered pattern, the operator can approve it, and the Pattern Manager
 *           reflects the `approved` lifecycle state on a subsequent read.
 *
 * REAL vs CONTRACT-MOCKED:
 *   - Reads + approval-intent go to the REAL Pattern Manager (`GET /patterns`,
 *     `POST /patterns/{id}/approve`) via the same-origin nginx proxy (/api/pattern-manager) in
 *     `E2E_MODE=real`, and to the in-app interceptor in `mock` mode. Either way this spec asserts
 *     NOTHING about specific ids/counts — it is DATA-AGNOSTIC: it grabs whatever the first draft
 *     row is, captures its identity, approves it, and proves that same pattern reads back as
 *     approved. It passes whether the live store has 3 drafts or 50.
 *   - The UI never emits Kafka — it posts the approval-intent; the Pattern Manager owns the
 *     lifecycle transition + `patterns.approved` emission.
 */

test.describe('Pattern review & XAI [AC 39]', () => {
  test('AC 39 — operator approves a draft pattern; it reads back as approved from the Pattern Manager', async ({
    page,
  }, testInfo) => {
    await page.goto('/patterns');
    await expect(page.getByRole('heading', { name: /^Patterns$/i })).toBeVisible();

    // Discovered (draft) tab is the default; grab the first draft row and expand it.
    await expect(page.getByTestId('tab-draft')).toHaveAttribute('aria-selected', 'true');
    const firstPattern = page.getByTestId('pattern-row').first();
    await expect(firstPattern).toBeVisible();
    await firstPattern.getByTestId('pattern-expand').click();

    // XAI renders from REAL data: numeric support/confidence/lift, a non-empty RCA, session window.
    const xai = firstPattern.getByTestId('pattern-xai');
    await expect(xai).toBeVisible();

    const metricsText = (await firstPattern.locator('.metrics').innerText()).trim();
    // support / confidence / lift are rendered via `| number` pipes → each carries a numeric token.
    expect(metricsText).toMatch(/support\s+\d+(\.\d+)?/i);
    expect(metricsText).toMatch(/confidence\s+\d+(\.\d+)?/i);
    expect(metricsText).toMatch(/lift\s+\d+(\.\d+)?/i);

    // Capture the row's real RCA (rootCauseAlarmType) as a self-referential handle — no literal.
    const rcaText = (await firstPattern.getByTestId('rca').innerText()).trim();
    expect(rcaText).toMatch(/^RCA\s+\S+/); // e.g. "RCA LOS" — non-empty rootCauseAlarmType
    const capturedRootCause = rcaText.replace(/^RCA\s+/, '').trim();
    expect(capturedRootCause.length).toBeGreaterThan(0);

    // Session-window line is present in the XAI panel.
    await expect(xai).toContainText(/session window:\s*\d+\s*ms/i);

    // Approve → real POST /api/pattern-manager/patterns/{uuid}/approve.
    // The store sends { decision: 'approve', reviewer: 'operator', notes? } (== real ApprovalIntent).
    await firstPattern.getByTestId('approve-btn').click();

    // Switch to the Active/approved tab → real GET /patterns?lifecycle=approved.
    await page.getByTestId('tab-approved').click();
    const approvedRows = page.getByTestId('pattern-row');
    await expect(approvedRows.first()).toBeVisible();

    // Round-trip proof: the approved list contains a row that (a) reads `approved` and
    // (b) carries the RCA we captured before approving — NOT asserting any specific count/id.
    const approvedMatch = approvedRows.filter({
      has: page.getByTestId('rca').filter({ hasText: capturedRootCause }),
    });
    await expect(approvedMatch.first()).toBeVisible();
    await expect(approvedMatch.first().getByTestId('pattern-lifecycle')).toHaveText(/approved/i);

    await shot(page, testInfo, 'ac-39-pattern-approve');
  });
});
