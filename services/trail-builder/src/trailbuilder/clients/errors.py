"""Integration-point error types."""

from __future__ import annotations


class IntegrationError(RuntimeError):
    """A collaborator call failed after retries or returned an unusable shape.

    Carries a ``reason`` ("topology" | "knowledge") for the build_failures_total
    metric label.
    """

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message)
        self.reason = reason
