"""Alembic environment — pinned to the owned ``trailbuilder`` schema.

``version_table_schema='trailbuilder'`` keeps the migration-history table INSIDE
the owned schema (never ``public``); ``include_schemas=True`` scopes
autogenerate/compare to it. Touches only ``trailbuilder`` (single-owner rule).
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import engine_from_config, pool, text

from trailbuilder.db import tables  # noqa: F401  (registers tables on metadata)
from trailbuilder.db.metadata import SCHEMA, metadata

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
        # The version table lives INSIDE the owned schema (version_table_schema=SCHEMA).
        # On a fresh database Alembic creates that table BEFORE running revision 0001
        # (which is the CREATE SCHEMA migration), so the schema must already exist or
        # the version-table CREATE fails with InvalidSchemaName. Ensure it idempotently
        # up front — touches only the owned schema (single-owner rule); 0001 keeps its
        # own idempotent CREATE SCHEMA for source/CLI runs.
        connection.execute(text(f'CREATE SCHEMA IF NOT EXISTS "{SCHEMA}"'))
        connection.commit()
        _configure(connection)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
