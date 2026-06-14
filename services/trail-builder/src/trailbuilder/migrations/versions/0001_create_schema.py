"""create the owned trailbuilder schema (idempotent, first migration)

Revision ID: 0001_create_schema
Revises:
Create Date: 2026-06-13
"""

from __future__ import annotations

from alembic import op

revision = "0001_create_schema"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Idempotent so a re-deploy or a racing second instance is safe; touches only
    # the owned schema (single-owner rule), never public or a shared baseline.
    op.execute("CREATE SCHEMA IF NOT EXISTS trailbuilder")


def downgrade() -> None:
    op.execute("DROP SCHEMA IF EXISTS trailbuilder CASCADE")
