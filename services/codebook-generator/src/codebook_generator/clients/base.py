"""Shared HTTP-client mechanics: bounded retry with exponential backoff.

Integration-point failures (5xx, connection errors) are retried with backoff bounded by
``INTEGRATION_MAX_RETRIES`` / ``INTEGRATION_BACKOFF_MS`` (config — no literals here). 4xx is
treated as unrecoverable. Exhaustion raises :class:`IntegrationError`, which the pipeline
maps to a DLQ route for the triggering event.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import TypeVar

import httpx

from ..logging_config import get_logger

logger = get_logger(__name__)

T = TypeVar("T")


class IntegrationError(RuntimeError):
    """Raised when an integration-point call fails unrecoverably (after retries/4xx)."""


def request_with_retry(
    fn: Callable[[], httpx.Response],
    *,
    max_retries: int,
    backoff_ms: int,
    sleep: Callable[[float], None] = time.sleep,
) -> httpx.Response:
    """Execute ``fn`` with bounded retry on 5xx / transport errors.

    Args:
        fn: a zero-arg callable returning an ``httpx.Response``.
        max_retries: number of retries after the first attempt (>= 0).
        backoff_ms: base backoff in milliseconds; doubled per attempt.
        sleep: injectable sleep (tests pass a no-op).

    Raises:
        IntegrationError: on 4xx, on 5xx after exhaustion, or on transport errors
            after exhaustion.
    """
    attempts = max_retries + 1
    last_exc: Exception | None = None
    for attempt in range(attempts):
        try:
            response = fn()
        except httpx.HTTPError as exc:
            last_exc = exc
            logger.warning("integration transport error (attempt %d): %s", attempt + 1, exc)
        else:
            if 400 <= response.status_code < 500:
                raise IntegrationError(
                    f"integration returned {response.status_code} (unrecoverable): "
                    f"{response.request.method} {response.request.url}"
                )
            if response.status_code >= 500:
                last_exc = IntegrationError(
                    f"integration returned {response.status_code}: "
                    f"{response.request.method} {response.request.url}"
                )
                logger.warning("integration 5xx (attempt %d): %s", attempt + 1, last_exc)
            else:
                return response
        if attempt < attempts - 1:
            sleep((backoff_ms * (2**attempt)) / 1000.0)
    raise IntegrationError(f"integration call exhausted after {attempts} attempts: {last_exc}")
