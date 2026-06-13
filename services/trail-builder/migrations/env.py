"""Alembic environment — pinned to the owned ``trailbuilder`` schema.

``version_table_schema='trailbuilder'`` keeps the migration-history table INSIDE
the owned schema (never ``public``); ``include_schemas=True`` scopes
autogenerate/compare to it. Touches only ``trailbuilder`` (single-owner rule).
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import engine_from_config, pool

from trailbuilder.db.metadata import SCHEMA, metadata
from trailbuilder.db import tables  # noqa: F401  (registers tables on metadata)

config = context.config
target_metadata = metadata


def _configure(connection) -> None:  # type: ignore[no-untyped-def]
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        version_table="alembic_version",
        version_table_schema=SCHEMA,
        include_schemas=True,
    )


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        version_table="alembic_version",
        version_table_schema=SCHEMA,
        include_schemas=True,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        _configure(connection)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
