/**
 * Strongly-typed shape of the runtime environment configuration.
 *
 * Every backend base URL, the mock/real toggle, and the streaming refresh interval are
 * resolved from this object — NEVER hard-coded in application source (spec AC 51). In the
 * container the values are injected from Docker Compose environment variables at serve time
 * (see `assets/env.js` / the Dockerfile entrypoint); in unit tests the mock environment is
 * used and every integration point resolves to an MSW handler.
 */
export type IntegrationMode = 'mock' | 'real';

export interface ServiceBaseUrls {
  readonly topology: string;
  readonly trailBuilder: string;
  readonly patternManager: string;
  readonly knowledge: string;
  readonly correlationEngine: string;
  readonly alarmManager: string;
  readonly noiseFilter: string;
  readonly enrichmentChatter: string;
  readonly simulatorLabels: string;
  readonly codebook: string;
}

export interface AppEnvironment {
  readonly production: boolean;
  /** Global default mode; individual clients still resolve through ApiConfigService. */
  readonly integrationMode: IntegrationMode;
  readonly serviceBaseUrls: ServiceBaseUrls;
  /** Default streaming poll interval in ms (operator-adjustable at runtime). */
  readonly streamingRefreshIntervalMs: number;
  /** Gates the demo/eval Simulator-labels RCA join; off in production. */
  readonly rcaLabelsEnabled: boolean;
  /** Domain scope for topology/trail/knowledge queries (Core IP MVP). */
  readonly domain: string;
  /** Snapshot id used for topology/trail queries (`current` by default). */
  readonly snapshotId: string;
  readonly logLevel: 'debug' | 'info' | 'warn' | 'error';
}
