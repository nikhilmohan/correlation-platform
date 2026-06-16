// Default runtime environment overlay (mock mode).
//
// This file is a placeholder shipped with the static bundle. In the Docker container it is
// OVERWRITTEN at startup by docker-entrypoint.sh, which materialises window.__ACP_ENV__ from
// the container's environment variables (INTEGRATION_MODE, *_API_BASE_URL, etc.) so the same
// immutable bundle can be wired to any backend stack with no rebuild.
//
// With no overlay (or this default), environment.ts falls back to compiled mock-mode defaults.
window.__ACP_ENV__ = {};
