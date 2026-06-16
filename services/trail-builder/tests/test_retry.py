"""Unit tests for the shared HTTP bounded-retry backoff helper, and a guard that
the integration clients actually honour ``HTTP_RETRY_BACKOFF_MS`` between retries.
"""

from __future__ import annotations

import httpx
import pytest

from trailbuilder.clients import _retry
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.clients.policy_client import KnowledgePolicyClient
from trailbuilder.clients.topology_client import TopologyClient

BASE = "http://retry.test"


def test_backoff_sleeps_between_attempts_not_after_last(monkeypatch) -> None:
    slept: list[float] = []
    monkeypatch.setattr(_retry.time, "sleep", lambda s: slept.append(s))
    # 3 attempts, 200ms backoff -> sleep before attempts 1 and 2, not after the last.
    for i in range(3):
        _retry.backoff_before_retry(i, attempts=3, backoff_ms=200)
    assert slept == [0.2, 0.2]


def test_backoff_no_sleep_when_disabled(monkeypatch) -> None:
    slept: list[float] = []
    monkeypatch.setattr(_retry.time, "sleep", lambda s: slept.append(s))
    _retry.backoff_before_retry(0, attempts=3, backoff_ms=0)
    assert slept == []


def test_topology_client_honours_backoff_between_retries(settings, monkeypatch) -> None:
    """HTTP_RETRY_MAX=3 + HTTP_RETRY_BACKOFF_MS=200 -> 3 failed GETs with a backoff
    sleep between each retry (2 sleeps), then an IntegrationError."""
    s = settings.model_copy(update={"http_retry_max": 3, "http_retry_backoff_ms": 200})
    slept: list[float] = []
    monkeypatch.setattr(_retry.time, "sleep", lambda x: slept.append(x))

    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get("/topology/nodes").mock(return_value=httpx.Response(503))
    with router:
        client = TopologyClient(s, client=httpx.Client(base_url=BASE))
        with pytest.raises(IntegrationError):
            client._list_nodes("Node", "core-ip", "current")
    assert slept == [0.2, 0.2]  # between the 3 attempts, never after the last


def test_policy_client_honours_backoff_between_retries(settings, monkeypatch) -> None:
    """The Knowledge policy client applies the same configured backoff between retries."""
    s = settings.model_copy(update={"http_retry_max": 2, "http_retry_backoff_ms": 50})
    slept: list[float] = []
    monkeypatch.setattr(_retry.time, "sleep", lambda x: slept.append(x))

    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get("/domains/core-ip/trail-policies/core-ip%2FtrailPolicy%2Fdefault").mock(
        return_value=httpx.Response(500)
    )
    with router:
        client = KnowledgePolicyClient(s, client=httpx.Client(base_url=BASE))
        with pytest.raises(IntegrationError):
            client.get_policy("core-ip")
    assert slept == [0.05]  # 2 attempts -> one backoff between them
