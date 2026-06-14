"""Table definitions for the ``trailbuilder`` schema (design Data model).

``trail`` (one row per trail), ``trail_member`` (trail-to-managedObjectId,
including ``Interface:*`` members), and ``processed_event`` (eventId dedupe).
All three live inside ``trailbuilder`` via the schema-pinned metadata.
"""

from __future__ import annotations

from sqlalchemy import (
    TIMESTAMP,
    BigInteger,
    CheckConstraint,
    Column,
    ForeignKey,
    Index,
    Integer,
    Table,
    Text,
    UniqueConstraint,
)

from .metadata import metadata

# BIGSERIAL on PostgreSQL; plain INTEGER (rowid-aliased autoincrement) on SQLite,
# where only an INTEGER PRIMARY KEY auto-populates. Same logical type, dialect-correct.
_AUTO_BIGINT = BigInteger().with_variant(Integer(), "sqlite")

trail = Table(
    "trail",
    metadata,  # -> trailbuilder.trail
    Column("trail_id", Text, primary_key=True),
    Column("domain", Text, nullable=False),
    Column("snapshot_id", Text, nullable=False),
    Column("seed_managed_object_id", Text, nullable=False),
    Column("igp_area", Text, nullable=True),
    Column("srlg_group", Text, nullable=True),
    Column("member_count", Integer, nullable=False),
    Column("built_at", TIMESTAMP(timezone=True), nullable=False),
    CheckConstraint("member_count > 0", name="ck_trail_member_count_positive"),
    Index("idx_trail_domain_snapshot", "domain", "snapshot_id"),
)

trail_member = Table(
    "trail_member",
    metadata,  # -> trailbuilder.trail_member
    Column("id", _AUTO_BIGINT, primary_key=True, autoincrement=True),
    Column(
        "trail_id",
        Text,
        ForeignKey("trailbuilder.trail.trail_id", ondelete="CASCADE"),
        nullable=False,
    ),
    Column("domain", Text, nullable=False),
    Column("snapshot_id", Text, nullable=False),
    Column("managed_object_id", Text, nullable=False),
    Column("object_type", Text, nullable=False),
    UniqueConstraint("trail_id", "managed_object_id", name="uq_member"),
    Index("idx_member_domain_object", "domain", "managed_object_id"),
    Index("idx_member_trail", "trail_id"),
)

processed_event = Table(
    "processed_event",
    metadata,  # -> trailbuilder.processed_event
    Column("event_id", Text, primary_key=True),
    Column("snapshot_id", Text),
    Column("domain", Text),
    Column("processed_at", TIMESTAMP(timezone=True), nullable=False),
)
