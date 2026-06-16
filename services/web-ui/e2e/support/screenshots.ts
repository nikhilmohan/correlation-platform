import { type Page, type TestInfo } from '@playwright/test';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Per-test screenshot helper for the AC 33.x journey tests.
 *
 * Captures a full-page screenshot to a DETERMINISTIC directory with a STABLE filename per test, so
 * the integration-tester can find and upload them as a GitHub Actions artifact without guessing
 * Playwright's hashed paths. The filename is suffixed with the run mode so a real-stack capture and
 * a mock capture never overwrite each other.
 *
 *   e2e/__screenshots__/<name>.<mode>.png   (e.g. ac-33-1-geo-map.real.png)
 *
 * The directory is gitignored — screenshots are CI artifacts, never committed binaries. The helper
 * also attaches the image to the Playwright HTML report so it is viewable in the report too.
 */
export const SCREENSHOT_DIR = path.resolve(HERE, '..', '__screenshots__');

export async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const mode = (process.env['E2E_MODE'] ?? 'mock') as 'mock' | 'real';
  const file = path.join(SCREENSHOT_DIR, `${name}.${mode}.png`);
  const buffer = await page.screenshot({ path: file, fullPage: true });
  // Also surface it in the Playwright HTML report for at-a-glance review.
  await testInfo.attach(`${name}.${mode}.png`, { body: buffer, contentType: 'image/png' });
}
