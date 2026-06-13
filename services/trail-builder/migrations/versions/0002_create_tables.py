"""create trail / trail_member / processed_event tables in trailbuilder

Revision ID: 0002_create_tables
Revises: 0001_create_schema
Create Date: 2026-06-13
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002_create_tables"
down_revision = "0001_create_schema"
branch_labels = None
depends_on = None

SCHEMA = "trailbuilder"


def upgrade() -> None:
    op.create_table(
        "trail",
        sa.Column("trail_id", sa.Text(), primary_key=True),
        sa.Column("domain", sa.Text(), nullable=False),
        sa.Column("snapshot_id", sa.Text(), nullable=False),
        sa.Column("seed_managed_object_id", sa.Text(), nullable=False),
        sa.Column("igp_area", sa.Text(), nullable=True),
        sa.Column("srlg_group", sa.Text(), nullable=True),
        sa.Column("member_count", sa.Integer(), nullable=False),
        sa.Column("built_at", sa.TIMESTAMP(timezone=True), nullable=False),
        sa.CheckConstraint("member_count > 0", name="ck_trail_member_count_positive"),
        schema=SCHEMA,
    )
    op.create_index(
        "idx_trail_domain_snapshot", "trail", ["domain", "snapshot_id"], schema=SCHEMA
    )
    op.create_table(
        "trail_member",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("trail_id", sa.Text(), nullable=False),
        sa.Column("domain", sa.Text(), nullable=False),
        sa.Column("snapshot_id", sa.Text(), nullable=False),
        sa.Column("managed_object_id", sa.Text(), nullable=False),
        sa.Column("object_type", sa.Text(), nullable=False),
        sa.ForeignKeyConstraint(
            ["trail_id"],
            [f"{SCHEMA}.trail.trail_id"],
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("trail_id", "managed_object_id", name="uq_member"),
        schema=SCHEMA,
    )
    op.create_index(
        "idx_member_domain_object",
        "trail_member",
        ["domain", "managed_object_id"],
        schema=SCHEMA,
    )
    op.create_index("idx_member_trail", "trail_member", ["trail_id"], schema=SCHEMA)
    op.create_table(
        "processed_event",
        sa.Column("event_id", sa.Text(), primary_key=True),
        sa.Column("snapshot_id", sa.Text(), nullable=True),
        sa.Column("domain", sa.Text(), nullable=True),
        sa.Column("processed_at", sa.TIMESTAMP(timezone=True), nullable=False),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("processed_event", schema=SCHEMA)
    op.drop_index("idx_member_trail", table_name="trail_member", schema=SCHEMA)
    op.drop_index("idx_member_domain_object", table_name="trail_member", schema=SCHEMA)
    op.drop_table("trail_member", schema=SCHEMA)
    op.drop_index("idx_trail_domain_snapshot", table_name="trail", schema=SCHEMA)
    op.drop_table("trail", schema=SCHEMA)
