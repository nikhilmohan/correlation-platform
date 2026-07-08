"""RunGuard — the single active-run mutex shared across the P3 synth and P2 mine triggers.

Both the synth run (``POST /synth/run``) and the mine-corpus run (``POST /mine/run``) drive the
Simulator's single producer, so at most ONE may be active at a time. A shared :class:`RunGuard`
gives both run managers the same 409 concurrency gate: whichever manager acquires first holds the
guard, and the other manager's ``start`` sees it busy and raises :class:`RunConflict` (handler →
409) — regardless of whether the active run is a synth or a mine run. The guard retains the last
run's id/kind while idle so a status handler can still report the most recent run.
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass


class RunConflict(RuntimeError):
    """Raised when a run is already active on the shared guard (handler → 409)."""

    def __init__(self, active_run_id: str | None) -> None:
        super().__init__("a run is already in progress")
        self.active_run_id = active_run_id


@dataclass(frozen=True)
class GuardSnapshot:
    """A consistent read of the guard state."""

    active: bool
    run_id: str | None
    kind: str | None  # "synth" | "mine" | None


class RunGuard:
    """Thread-safe single-active-run mutex shared by the synth + mine run managers."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active = False
        self._run_id: str | None = None
        self._kind: str | None = None

    def acquire(self, kind: str) -> str:
        """Reserve the guard for a new run of ``kind`` (returns its runId) or raise RunConflict."""
        with self._lock:
            if self._active:
                raise RunConflict(active_run_id=self._run_id)
            run_id = str(uuid.uuid4())
            self._active = True
            self._run_id = run_id
            self._kind = kind
            return run_id

    def release(self) -> None:
        """Mark the active run finished; retain its id/kind so idle status can still report it."""
        with self._lock:
            self._active = False

    def snapshot(self) -> GuardSnapshot:
        with self._lock:
            return GuardSnapshot(active=self._active, run_id=self._run_id, kind=self._kind)
