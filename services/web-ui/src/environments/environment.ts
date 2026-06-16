import { AppEnvironment, IntegrationMode } from './environment.model';

/**
 * Runtime-overridable environment overlay. In the container `assets/env.js` sets
 * `window.__ACP_ENV__` from Docker Compose environment variables before the app boots, so the
 * same static bundle serves any backend wiring with no rebuild. In dev/unit tests the overlay
 * is absent and these compiled defaults (mock mode) apply.
 */
interface RuntimeEnvOverlay {
  INTEGRATION_MODE?: IntegrationMode;
  TOPOLOGY_API_BASE_URL?: string;
  TRAIL_BUILDER_API_BASE_URL?: string;
  PATTERN_MANAGER_API_BASE_URL?: string;
  KNOWLEDGE_API_BASE_URL?: string;
  CORRELATION_ENGINE_API_BASE_URL?: string;
  ALARM_MANAGER_API_BASE_URL?: string;
  NOISE_FILTER_API_BASE_URL?: string;
  ENRICHMENT_CHATTER_API_BASE_URL?: string;
  SIMULATOR_LABELS_API_BASE_URL?: string;
  CODEBOOK_API_BASE_URL?: string;
  STREAMING_REFRESH_INTERVAL_MS?: string;
  TOPOLOGY_NODE_CAP?: string;
  RCA_LABELS_ENABLED?: string;
  DOMAIN?: string;
  SNAPSHOT_ID?: string;
  LOG_LEVEL?: AppEnvironment['logLevel'];
}

function overlay(): RuntimeEnvOverlay {
  const w = globalThis as unknown as { __ACP_ENV__?: RuntimeEnvOverlay };
  return w.__ACP_ENV__ ?? {};
}

const o = overlay();

export const environment: AppEnvironment = {
  production: false,
  integrationMode: o.INTEGRATION_MODE ?? 'mock',
  serviceBaseUrls: {
    // Under the mock toggle these are relative; MSW intercepts. Under real, the runtime
    // overlay supplies the Compose service addresses.
    topology: o.TOPOLOGY_API_BASE_URL ?? '/mock/topology',
    trailBuilder: o.TRAIL_BUILDER_API_BASE_URL ?? '/mock/trail-builder',
    patternManager: o.PATTERN_MANAGER_API_BASE_URL ?? '/mock/pattern-manager',
    knowledge: o.KNOWLEDGE_API_BASE_URL ?? '/mock/knowledge',
    correlationEngine: o.CORRELATION_ENGINE_API_BASE_URL ?? '/mock/correlation-engine',
    alarmManager: o.ALARM_MANAGER_API_BASE_URL ?? '/mock/alarm-manager',
    noiseFilter: o.NOISE_FILTER_API_BASE_URL ?? '/mock/noise-filter',
    enrichmentChatter: o.ENRICHMENT_CHATTER_API_BASE_URL ?? '/mock/enrichment',
    simulatorLabels: o.SIMULATOR_LABELS_API_BASE_URL ?? '/mock/simulator',
    codebook: o.CODEBOOK_API_BASE_URL ?? '/mock/codebook',
  },
  streamingRefreshIntervalMs: o.STREAMING_REFRESH_INTERVAL_MS
    ? Number(o.STREAMING_REFRESH_INTERVAL_MS)
    : 3000,
  topologyNodeCap: o.TOPOLOGY_NODE_CAP ? Number(o.TOPOLOGY_NODE_CAP) : 250,
  rcaLabelsEnabled: o.RCA_LABELS_ENABLED === 'true',
  domain: o.DOMAIN ?? 'core-ip',
  snapshotId: o.SNAPSHOT_ID ?? 'current',
  logLevel: o.LOG_LEVEL ?? 'info',
};
