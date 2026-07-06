#!/bin/sh
# Materialise the runtime environment overlay (window.__ACP_ENV__) from container environment
# variables, then hand off to nginx. This lets one immutable static bundle be wired to any
# backend stack (mock or real, any per-service base URL) with no rebuild — the same SPA code
# runs in every mode (spec §Config-switchable backends, AC 50/51).
set -eu

ENV_FILE="/usr/share/nginx/html/env.js"

# Only emit keys that are actually set, so unset vars fall back to the app's compiled defaults.
emit() {
  key="$1"; val="$2"
  if [ -n "${val}" ]; then
    printf '  %s: "%s",\n' "$key" "$val" >> "$ENV_FILE"
  fi
}

{
  echo "// Generated at container start by docker-entrypoint.sh — do not edit."
  echo "window.__ACP_ENV__ = {"
} > "$ENV_FILE"

emit INTEGRATION_MODE "${INTEGRATION_MODE:-}"
emit TOPOLOGY_API_BASE_URL "${TOPOLOGY_API_BASE_URL:-}"
emit TRAIL_BUILDER_API_BASE_URL "${TRAIL_BUILDER_API_BASE_URL:-}"
emit PATTERN_MANAGER_API_BASE_URL "${PATTERN_MANAGER_API_BASE_URL:-}"
emit KNOWLEDGE_API_BASE_URL "${KNOWLEDGE_API_BASE_URL:-}"
emit CORRELATION_ENGINE_API_BASE_URL "${CORRELATION_ENGINE_API_BASE_URL:-}"
emit ALARM_MANAGER_API_BASE_URL "${ALARM_MANAGER_API_BASE_URL:-}"
emit NOISE_FILTER_API_BASE_URL "${NOISE_FILTER_API_BASE_URL:-}"
emit ENRICHMENT_CHATTER_API_BASE_URL "${ENRICHMENT_CHATTER_API_BASE_URL:-}"
emit SIMULATOR_LABELS_API_BASE_URL "${SIMULATOR_LABELS_API_BASE_URL:-}"
emit SIMULATOR_API_BASE_URL "${SIMULATOR_API_BASE_URL:-}"
emit CODEBOOK_API_BASE_URL "${CODEBOOK_API_BASE_URL:-}"
emit STREAMING_REFRESH_INTERVAL_MS "${STREAMING_REFRESH_INTERVAL_MS:-}"
emit RCA_LABELS_ENABLED "${RCA_LABELS_ENABLED:-}"
emit DOMAIN "${DOMAIN:-}"
emit SNAPSHOT_ID "${SNAPSHOT_ID:-}"
emit LOG_LEVEL "${LOG_LEVEL:-}"

echo "};" >> "$ENV_FILE"

# Runs as a /docker-entrypoint.d/*.sh hook under the stock nginx entrypoint, which execs the
# CMD (nginx) after all hooks complete — so this script just generates the overlay and returns.
