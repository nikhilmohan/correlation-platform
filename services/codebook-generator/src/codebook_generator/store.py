"""Codebook Store — owned PostgreSQL schema ``codebook`` (SQLAlchemy Core + pg8000).

Tables (created by the ``yoyo`` migrations, never by this module) are mirrored here as
Core ``Table`` metadata, all schema-qualified to ``codebook``. The writer's
:meth:`CodebookStore.persist_codebook` runs the atomic supersede-then-insert in one
transaction (demote the prior ``active=true`` row for the ``(domain, snapshot_id)`` key to
``active=false``, then insert the new ``active=true`` codebook + its scenarios), so the
partial-unique index ``uq_codebooks_one_active`` always sees exactly one active row per key.
Dedup is on the envelope ``event_id`` via ``processed_events``.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import (
    ARRAY,
    JSON,
    Boolean,
    Column,
    DateTime,
    Integer,
    MetaData,
    Table,
    Text,
    create_engine,
    delete,
    insert,
    select,
    update,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Engine

from .models import Codebook, PredictedSymptom, Scenario

SCHEMA = "codebook"

# Portable column types: Postgres uses JSONB / text[] (the authoritative migration DDL);
# other dialects (SQLite, used in unit tests) fall back to JSON. The same Table metadata
# therefore works at runtime (Postgres) and in dialect-agnostic unit tests.
_jsonb = JSON().with_variant(JSONB(), "postgresql")
_text_array = JSON().with_variant(ARRAY(Text()), "postgresql")

_metadata = MetaData(schema=SCHEMA)

codebooks_table = Table(
    "codebooks",
    _metadata,
    Column("codebook_id", Text, primary_key=True),
    Column("snapshot_id", Text, nullable=False),
    Column("domain", Text, nullable=False),
    Column("active", Boolean, nullable=False, default=True),
    Column("scenario_count", Integer, nullable=False),
    Column("knowledge_version", Text, nullable=True),
    Column("compiled_at", DateTime(timezone=True), nullable=False),
)

scenarios_table = Table(
    "scenarios",
    _metadata,
    Column("scenario_id", Text, primary_key=True),
    Column("codebook_id", Text, nullable=False),
    Column("fault_origin_object_id", Text, nullable=False),
    Column("fault_origin_type", Text, nullable=False),
    Column("predicted_symptoms", _jsonb, nullable=False),
    Column("trail_ids", _text_array, nullable=False),
)

processed_events_table = Table(
    "processed_events",
    _metadata,
    Column("event_id", Text, primary_key=True),
    Column("codebook_id", Text, nullable=True),
    Column("processed_at", DateTime(timezone=True), nullable=False),
)


def new_codebook_id() -> str:
    """Mint a fresh codebook id (``cb-{uuid4}``)."""
    return f"cb-{uuid.uuid4()}"


def scenario_id(codebook_id: str, fault_origin_object_id: str) -> str:
    """Stable scenario id within a codebook: ``{codebook_id}:{fault_origin_object_id}``."""
    return f"{codebook_id}:{fault_origin_object_id}"


class CodebookStore:
    """Reader/writer for the Codebook Store."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @classmethod
    def from_url(cls, database_url: str) -> CodebookStore:
        """Build a store from a SQLAlchemy URL (pg8000 driver expected in prod)."""
        return cls(create_engine(database_url, future=True))

    @property
    def engine(self) -> Engine:
        return self._engine

    def ping(self) -> bool:
        """Readiness check: a trivial query against the DB."""
        try:
            with self._engine.connect() as conn:
                conn.execute(select(1))
            return True
        except Exception:  # noqa: BLE001 — readiness must never raise
            return False

    # --- Idempotency ---
    def already_processed(self, event_id: str) -> str | None:
        """Return the codebook_id produced by ``event_id`` if already processed, else None.

        Distinguishes "not processed" from "processed with no codebook" via a sentinel.
        """
        with self._engine.connect() as conn:
            row = conn.execute(
                select(processed_events_table.c.codebook_id).where(
                    processed_events_table.c.event_id == event_id
                )
            ).first()
        if row is None:
            return None
        return row[0] if row[0] is not None else ""

    # --- Persistence (atomic supersede-then-insert) ---
    def persist_codebook(
        self,
        *,
        event_id: str,
        snapshot_id: str,
        domain: str,
        scenarios: list[Scenario],
        knowledge_version: str | None = None,
    ) -> str:
        """Persist a codebook + scenarios atomically and set it active for the key.

        Demotes any prior ``active=true`` codebook for ``(domain, snapshot_id)``, inserts the
        new active codebook and its scenarios, and records ``event_id`` in
        ``processed_events`` — all in one transaction.

        Returns:
            the freshly minted ``codebook_id``.
        """
        codebook_id = new_codebook_id()
        now = datetime.now(UTC)
        with self._engine.begin() as conn:
            conn.execute(
                update(codebooks_table)
                .where(
                    codebooks_table.c.domain == domain,
                    codebooks_table.c.snapshot_id == snapshot_id,
                    codebooks_table.c.active.is_(True),
                )
                .values(active=False)
            )
            conn.execute(
                insert(codebooks_table).values(
                    codebook_id=codebook_id,
                    snapshot_id=snapshot_id,
                    domain=domain,
                    active=True,
                    scenario_count=len(scenarios),
                    knowledge_version=knowledge_version,
                    compiled_at=now,
                )
            )
            if scenarios:
                conn.execute(
                    insert(scenarios_table),
                    [
                        {
                            "scenario_id": scenario_id(codebook_id, s.faultOriginObjectId),
                            "codebook_id": codebook_id,
                            "fault_origin_object_id": s.faultOriginObjectId,
                            "fault_origin_type": s.faultOriginType,
                            "predicted_symptoms": [sym.model_dump() for sym in s.predictedSymptoms],
                            "trail_ids": list(s.trailIds),
                        }
                        for s in scenarios
                    ],
                )
            conn.execute(
                insert(processed_events_table).values(
                    event_id=event_id,
                    codebook_id=codebook_id,
                    processed_at=now,
                )
            )
        return codebook_id

    def record_processed_no_codebook(self, event_id: str) -> None:
        """Mark ``event_id`` processed without a codebook (e.g. DLQ-routed)."""
        with self._engine.begin() as conn:
            conn.execute(
                insert(processed_events_table).values(
                    event_id=event_id,
                    codebook_id=None,
                    processed_at=datetime.now(UTC),
                )
            )

    # --- Reads ---
    def _codebook_meta_row(self, conn: Any, codebook_id: str) -> dict[str, Any] | None:
        row = (
            conn.execute(
                select(codebooks_table).where(codebooks_table.c.codebook_id == codebook_id)
            )
            .mappings()
            .first()
        )
        return dict(row) if row else None

    def get_codebook_meta(self, codebook_id: str) -> dict[str, Any] | None:
        """Return codebook metadata row (as a dict) or None."""
        with self._engine.connect() as conn:
            return self._codebook_meta_row(conn, codebook_id)

    def get_active(self, domain: str, snapshot_id: str) -> dict[str, Any] | None:
        """Return the single active codebook metadata for ``(domain, snapshot_id)`` or None."""
        with self._engine.connect() as conn:
            row = (
                conn.execute(
                    select(codebooks_table).where(
                        codebooks_table.c.domain == domain,
                        codebooks_table.c.snapshot_id == snapshot_id,
                        codebooks_table.c.active.is_(True),
                    )
                )
                .mappings()
                .first()
            )
        return dict(row) if row else None

    def list_by_domain(self, domain: str) -> list[dict[str, Any]]:
        """Return all codebooks for a domain, newest first."""
        with self._engine.connect() as conn:
            rows = (
                conn.execute(
                    select(codebooks_table)
                    .where(codebooks_table.c.domain == domain)
                    .order_by(codebooks_table.c.compiled_at.desc())
                )
                .mappings()
                .all()
            )
        return [dict(r) for r in rows]

    def list_by_snapshot(self, snapshot_id: str) -> list[dict[str, Any]]:
        """Return all codebooks for a snapshot, newest first."""
        with self._engine.connect() as conn:
            rows = (
                conn.execute(
                    select(codebooks_table)
                    .where(codebooks_table.c.snapshot_id == snapshot_id)
                    .order_by(codebooks_table.c.compiled_at.desc())
                )
                .mappings()
                .all()
            )
        return [dict(r) for r in rows]

    def get_scenarios(
        self, codebook_id: str, fault_origin_type: str | None = None
    ) -> list[Scenario]:
        """Return the scenarios for a codebook, optionally filtered by fault-origin type."""
        stmt = select(scenarios_table).where(scenarios_table.c.codebook_id == codebook_id)
        if fault_origin_type:
            stmt = stmt.where(scenarios_table.c.fault_origin_type == fault_origin_type)
        stmt = stmt.order_by(scenarios_table.c.scenario_id)
        with self._engine.connect() as conn:
            rows = conn.execute(stmt).mappings().all()
        return [_row_to_scenario(r) for r in rows]

    def get_scenario(self, codebook_id: str, scenario_id_value: str) -> Scenario | None:
        """Return a single scenario by id within a codebook, or None."""
        with self._engine.connect() as conn:
            row = (
                conn.execute(
                    select(scenarios_table).where(
                        scenarios_table.c.codebook_id == codebook_id,
                        scenarios_table.c.scenario_id == scenario_id_value,
                    )
                )
                .mappings()
                .first()
            )
        return _row_to_scenario(row) if row else None

    def get_full_codebook(self, codebook_id: str) -> Codebook | None:
        """Return codebook metadata + scenarios, or None when unknown."""
        meta = self.get_codebook_meta(codebook_id)
        if meta is None:
            return None
        return Codebook(
            codebookId=meta["codebook_id"],
            snapshotId=meta["snapshot_id"],
            domain=meta["domain"],
            scenarioCount=meta["scenario_count"],
            knowledgeVersion=meta.get("knowledge_version"),
            scenarios=self.get_scenarios(codebook_id),
        )

    # --- Test/maintenance helper ---
    def clear(self) -> None:
        """Delete all rows (test utility; respects FK order)."""
        with self._engine.begin() as conn:
            conn.execute(delete(scenarios_table))
            conn.execute(delete(processed_events_table))
            conn.execute(delete(codebooks_table))


def _row_to_scenario(row: Any) -> Scenario:
    return Scenario(
        scenarioId=row["scenario_id"],
        faultOriginObjectId=row["fault_origin_object_id"],
        faultOriginType=row["fault_origin_type"],
        predictedSymptoms=[PredictedSymptom.model_validate(s) for s in row["predicted_symptoms"]],
        trailIds=list(row["trail_ids"]),
    )
