/**
 * Shared E2E run-mode helper.
 *
 * `E2E_MODE` selects how the suite runs (see playwright.config.ts header):
 *   - 'mock'  (default): the SPA serves every backend from its in-process mock interceptor;
 *             the suite is fully deterministic and needs no live stack. Used for local authoring
 *             and the spec-well-formedness gate.
 *   - 'real'  (integration): the docker-compose SPA is wired to the REAL P1 read-API stack;
 *             P1 flows hit the real services, while not-yet-built P2/P3 + chatter collaborators
 *             are stubbed at the contract boundary (see contract-mocks.ts).
 *
 * Specs use `MODE` only to decide WHICH backend a value comes from (real vs mock) and to install
 * the contract mocks in `real` mode — never to change what is asserted.
 */
export type E2eMode = 'mock' | 'real';

export const MODE: E2eMode = (process.env['E2E_MODE'] as E2eMode) ?? 'mock';

export const IS_MOCK = MODE === 'mock';
export const IS_REAL = MODE === 'real';
