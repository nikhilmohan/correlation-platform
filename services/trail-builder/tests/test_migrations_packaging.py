"""Install-robust resolution of the Alembic migrations (container-packaging regression).

The P1 integration gate caught the service exiting 1 in its built image with::

    alembic.util.exc.CommandError: Path doesn't exist:
        /opt/venv/lib/python3.13/migrations

Root cause: the old runtime resolved ``script_location`` from ``__file__.parent.parent.parent``
— the service-root offset that only holds for a *source checkout*. Under a non-editable wheel
install (site-packages, i.e. the Docker image) that offset points outside the package and the
migrations are not found.

The fix ships the migrations as **package data inside the ``trailbuilder`` package** and resolves
them via :func:`importlib.resources.files`, so the path holds identically for an editable
checkout, a non-editable wheel install, AND the Docker image. These tests pin that contract: they
resolve via ``importlib.resources`` (NOT a source-tree offset), so they only pass when the
migrations actually ship with the installed package.
"""

from __future__ import annotations

import os
from importlib.resources import files
from pathlib import Path

from trailbuilder.runtime import MIGRATIONS_DIR_ENV, migrations_dir

# Resolve the migrations the same install-robust way the runtime does — over the INSTALLED
# package, so this matches a non-editable wheel install where the files live under site-packages.
_MIGRATIONS = Path(str(files("trailbuilder") / "migrations")).resolve()


def test_migrations_dir_resolves_to_packaged_migrations() -> None:
    """migrations_dir() resolves to the packaged migrations dir for ANY install layout."""
    resolved = migrations_dir()
    assert resolved == _MIGRATIONS


def test_packaged_migrations_ship_env_and_versions() -> None:
    """The wheel ships env.py plus both versioned revisions (the script_location contents)."""
    assert (_MIGRATIONS / "env.py").is_file()
    versions = _MIGRATIONS / "versions"
    assert (versions / "0001_create_schema.py").is_file()
    assert (versions / "0002_create_tables.py").is_file()


def test_first_migration_creates_owned_schema_idempotently() -> None:
    """The first revision keeps the idempotent CREATE SCHEMA for the owned schema."""
    sql = (_MIGRATIONS / "versions" / "0001_create_schema.py").read_text()
    assert "CREATE SCHEMA IF NOT EXISTS trailbuilder" in sql


def test_migrations_dir_honours_env_override(tmp_path: Path) -> None:
    """$TRAILBUILDER_MIGRATIONS_DIR overrides the packaged location (operator escape hatch)."""
    prior = os.environ.get(MIGRATIONS_DIR_ENV)
    os.environ[MIGRATIONS_DIR_ENV] = str(tmp_path)
    try:
        assert migrations_dir() == tmp_path.resolve()
    finally:
        if prior is None:
            os.environ.pop(MIGRATIONS_DIR_ENV, None)
        else:
            os.environ[MIGRATIONS_DIR_ENV] = prior
