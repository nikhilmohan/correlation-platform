"""Schema migration runner (yoyo-migrations).

Applies the versioned plain-SQL migrations packaged at ``codebook_generator/migrations/``
against ``DATABASE_URL`` at container startup, BEFORE the consumer/API start. Idempotent
(yoyo's applied-migration ledger + ``IF NOT EXISTS`` DDL). Startup aborts (non-zero exit /
raised error) if migrations fail (spec criteria 27-29).

Resolution is **install-robust**: the SQL ships as package data inside the
``codebook_generator`` package, so it is located the same way whether the service runs from a
source checkout, an installed wheel, or the Docker image — never by walking ``__file__``
parents (which breaks under a non-editable / site-packages install). An optional
``CODEBOOK_MIGRATIONS_DIR`` env var overrides the location (operator escape hatch).
"""

from __future__ import annotations

import os
import re
from importlib.resources import files
from pathlib import Path

from yoyo import get_backend, read_migrations
from yoyo.backends import DatabaseBackend, PostgresqlBackend
from yoyo.connections import parse_uri

from .logging_config import get_logger

logger = get_logger(__name__)

#: Env var to override the migrations directory. When unset, the migrations packaged inside
#: the installed ``codebook_generator`` package are used.
MIGRATIONS_DIR_ENV = "CODEBOOK_MIGRATIONS_DIR"

#: Matches a SQLAlchemy driver-qualified scheme (``<dialect>+<driver>://``) at the start of a
#: URL, capturing the dialect so the ``+<driver>`` suffix can be stripped for yoyo.
_DRIVER_SCHEME_RE = re.compile(r"^([a-z][a-z0-9.]*)\+[a-z0-9.]+(://)", re.IGNORECASE)

#: Postgres schemes (with their SQLAlchemy ``+pg8000`` driver suffix) that the service drives
#: through pg8000 — the single pure-Python Postgres driver shared with the SQLAlchemy store.
_PG8000_SCHEMES = frozenset({"postgresql+pg8000", "postgres+pg8000", "pg8000"})


class _Pg8000Backend(PostgresqlBackend):
    """yoyo Postgres backend that drives migrations through **pg8000**.

    yoyo ships only psycopg2 / psycopg3 Postgres backends, but this service uses pg8000 (the
    pure-Python driver, no libpq) for the SQLAlchemy store. Rather than add a second,
    conflicting Postgres driver just for migrations, this backend reuses yoyo's
    :class:`PostgresqlBackend` (the ``codebook`` schema DDL, the applied-migration ledger,
    locking) but connects via pg8000 so the *same* driver applies the migrations and serves
    the store. pg8000's DB-API ``connect`` takes ``database=``/``user=`` (not psycopg2's
    ``dbname=``), so :meth:`connect` is overridden to map the parsed URI accordingly.
    """

    driver_module = "pg8000.dbapi"

    def connect(self, dburi: object) -> object:
        kwargs: dict[str, object] = dict(getattr(dburi, "args", {}) or {})
        self.schema = kwargs.pop("schema", None)
        if (database := getattr(dburi, "database", None)) is not None:
            kwargs["database"] = database
        if (username := getattr(dburi, "username", None)) is not None:
            kwargs["user"] = username
        if (password := getattr(dburi, "password", None)) is not None:
            kwargs["password"] = password
        if (port := getattr(dburi, "port", None)) is not None:
            kwargs["port"] = port
        if (hostname := getattr(dburi, "hostname", None)) is not None:
            kwargs["host"] = hostname
        connection = self.driver.connect(**kwargs)
        connection.autocommit = True
        return connection

    def begin(self) -> None:
        # yoyo's PostgresqlBackend.begin() guards against nested transactions via the
        # psycopg-only ``connection.info.transaction_status`` attribute, which pg8000 does not
        # expose. pg8000 runs autocommit here, so skip that guard and use the base BEGIN.
        DatabaseBackend.begin(self)


def yoyo_url(database_url: str) -> str:
    """Normalize a SQLAlchemy ``DATABASE_URL`` for yoyo's :func:`get_backend`.

    The service exposes a single ``DATABASE_URL`` consumed by two libraries with different
    scheme expectations: SQLAlchemy (``store.py``) needs the driver-qualified form
    (e.g. ``postgresql+pg8000://``), but yoyo's ``get_backend`` understands only a plain
    DB-API scheme (``postgresql://``) and rejects the ``+<driver>`` suffix
    ("Unrecognised database connection scheme"). This strips ``+<driver>`` from the scheme so
    yoyo accepts the URL, leaving the SQLAlchemy-facing value (used by ``store.py``) untouched.

    A URL with no driver suffix (e.g. ``postgresql://`` or ``sqlite://``) passes through
    unchanged.

    Args:
        database_url: the SQLAlchemy ``DATABASE_URL`` (may carry a ``+<driver>`` scheme).

    Returns:
        the same URL with any ``+<driver>`` scheme suffix removed.
    """
    return _DRIVER_SCHEME_RE.sub(r"\1\2", database_url, count=1)


def build_backend(database_url: str) -> DatabaseBackend:
    """Build the yoyo backend for ``database_url``, honouring the service's pg8000 driver.

    For the documented ``postgresql+pg8000://`` form (and equivalents), a pg8000-backed
    Postgres backend is used directly — keeping migrations on the same pure-Python driver as
    the SQLAlchemy store. Every other URL (e.g. the SQLite test backend) is normalized with
    :func:`yoyo_url` and resolved through yoyo's standard :func:`get_backend`.
    """
    scheme = parse_uri(database_url).scheme.lower()
    if scheme in _PG8000_SCHEMES:
        backend = _Pg8000Backend(parse_uri(database_url), "_yoyo_migration")
        # yoyo.get_backend() does this for its registered backends; we construct directly, so
        # create the lock/migration bookkeeping tables ourselves before applying migrations.
        backend.init_database()
        return backend
    return get_backend(yoyo_url(database_url))


def migrations_dir() -> Path:
    """Absolute path to the migrations directory, resolved install-robustly.

    Order of resolution:

    1. ``$CODEBOOK_MIGRATIONS_DIR`` if set (operator override).
    2. The ``migrations/`` package-data directory shipped *inside* the installed
       ``codebook_generator`` package (via :func:`importlib.resources.files`). This resolves
       identically for a source checkout, a wheel install, and the Docker image, because the
       SQL travels with the package rather than living at a fixed source-tree offset.
    """
    override = os.environ.get(MIGRATIONS_DIR_ENV)
    if override:
        return Path(override).resolve()
    return Path(str(files("codebook_generator") / "migrations")).resolve()


def apply_migrations(database_url: str, path: Path | None = None) -> None:
    """Apply all pending migrations against ``database_url``.

    Args:
        database_url: the SQLAlchemy ``DATABASE_URL`` (may carry a ``+<driver>`` scheme such
            as ``postgresql+pg8000://``); it is normalized to a plain DB-API scheme for yoyo.
        path: migrations directory; defaults to the packaged ``migrations/``.

    Raises:
        Exception: propagated from yoyo when a migration fails (startup must abort).
    """
    directory = path or migrations_dir()
    logger.info("applying migrations from %s", directory)
    # The same DATABASE_URL the SQLAlchemy store consumes verbatim (postgresql+pg8000://) is
    # resolved to a pg8000-backed yoyo backend here; other schemes are normalized for yoyo.
    backend = build_backend(database_url)
    migrations = read_migrations(str(directory))
    with backend.lock():
        backend.apply_migrations(backend.to_apply(migrations))
    logger.info("migrations applied")
