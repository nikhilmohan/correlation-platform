import { test as base, expect } from '@playwright/test';
import { IS_REAL, MODE } from './e2e-mode';
import { installContractMocks } from './contract-mocks';

/**
 * Shared test fixture. Before each test, in `real` mode it installs the contract-boundary mocks
 * for the not-yet-built P2/P3 + chatter collaborators (so P1 stays real, P2/P3 are
 * contract-shaped). In `mock` mode it does nothing — the in-app interceptor already serves
 * everything from OpenAPI-shaped fixtures.
 *
 * Specs import { test, expect } FROM HERE rather than from @playwright/test so the wiring is
 * uniform and the real-vs-mock boundary is applied in exactly one place.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    if (IS_REAL) {
      await installContractMocks(page);
    }
    await use(page);
  },
});

export { expect, MODE, IS_REAL };
