"""Regression guard for the persistent ``serve`` mode runtime dependency.

``simulator.serve.main`` calls ``uvicorn.run(...)`` behind a ``# pragma: no cover`` process
boundary, so the unit suite never imports uvicorn transitively. When the FastAPI app was only
exercised via ``TestClient`` (no server), uvicorn was not a declared runtime dependency and a
live ``python -m simulator serve`` crashed at startup with ``ModuleNotFoundError: No module
named 'uvicorn'``.

These tests fail fast (no server start) if that dependency silently disappears again:
  1. ``import uvicorn`` must succeed in the installed runtime.
  2. ``uvicorn`` must be declared in ``pyproject.toml`` ``[project].dependencies``.
"""

from __future__ import annotations

import tomllib
from pathlib import Path

# services/simulator/pyproject.toml — from tests/ go up one level to the service root.
_PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"


def test_uvicorn_importable() -> None:
    """serve.main's uvicorn.run(...) needs uvicorn present at runtime."""
    import uvicorn  # noqa: F401  (import is the assertion)


def test_uvicorn_declared_in_pyproject_dependencies() -> None:
    """uvicorn must be a declared runtime dependency, not an accidental transitive import."""
    assert _PYPROJECT.is_file(), f"pyproject.toml not found at {_PYPROJECT}"
    data = tomllib.loads(_PYPROJECT.read_text(encoding="utf-8"))
    dependencies = data["project"]["dependencies"]

    def _dist_name(requirement: str) -> str:
        # Strip version specifiers / extras / markers to the bare distribution name.
        name = requirement.strip()
        for sep in (";", "[", "==", ">=", "<=", "~=", "!=", ">", "<", " "):
            name = name.split(sep, 1)[0]
        return name.strip().lower()

    declared = {_dist_name(req) for req in dependencies}
    assert "uvicorn" in declared, (
        "uvicorn is not declared in [project].dependencies of "
        f"{_PYPROJECT}; the persistent `serve` mode (uvicorn.run) will crash at startup. "
        f"Declared: {sorted(declared)}"
    )
