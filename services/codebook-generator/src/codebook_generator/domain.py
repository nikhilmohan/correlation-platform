"""Domain resolution (spec task 2 / criterion 10).

Reads ``domain`` directly from the ``TrailsBuiltEvent`` payload. When absent (pre-#90
backward-compat events) returns the configured default. No Topology snapshot-metadata
lookup is made for domain resolution (OQ-4 resolved by contract #90).
"""

from __future__ import annotations

from typing import Any


def resolve_domain(payload: Any, default_domain: str) -> str:
    """Return the event's ``domain``, or ``default_domain`` when the field is absent/empty.

    Args:
        payload: the deserialized ``TrailsBuiltEvent`` payload (carries optional ``domain``).
        default_domain: the configured single-MVP-domain fallback (e.g. ``core-ip``).
    """
    domain = getattr(payload, "domain", None)
    if isinstance(domain, str) and domain.strip():
        return domain
    return default_domain
