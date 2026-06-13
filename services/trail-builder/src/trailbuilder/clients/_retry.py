"""Shared bounded-retry backoff for the HTTP integration clients.

Both :class:`TopologyClient` and :class:`KnowledgePolicyClient` retry transient
HTTP failures ``HTTP_RETRY_MAX`` times. This helper applies the configured
``HTTP_RETRY_BACKOFF_MS`` pause *between* attempts (never after the final one),
so a flapping collaborator is not hammered in a tight loop. The backoff value is
config-sourced (Settings) — never hard-coded.
"""

from __future__ import annotations

import time


def backoff_before_retry(attempt_index: int, attempts: int, backoff_ms: int) -> None:
    """Sleep ``backoff_ms`` before a retry, unless this was the last attempt.

    ``attempt_index`` is 0-based. No sleep is taken on the final attempt
    (``attempt_index == attempts - 1``) or when ``backoff_ms <= 0``.
    """
    if backoff_ms > 0 and attempt_index < attempts - 1:
        time.sleep(backoff_ms / 1000.0)
