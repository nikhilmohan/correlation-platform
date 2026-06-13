"""FROZEN HTTP request/response models for the trail-query + rebuild API.

These shapes are the single source of truth consumers (Codebook Generator,
Enrichment, Noise Filter, web-ui) build against. They are checked into
``openapi.json`` and MUST NOT drift without a contract change (P1-G4, P1-G10,
P2-GAP-09).
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class TrailsForObjectResponse(BaseModel):
    """getTrailsForObject — FROZEN (P1-G4). Intentionally minimal: ``trailIds`` only."""

    model_config = ConfigDict(extra="forbid")

    managedObjectId: str
    domain: str
    trailIds: list[str]


class TrailMember(BaseModel):
    """A trail member carrying BOTH the typed managedObjectId and its objectType."""

    model_config = ConfigDict(extra="forbid")

    managedObjectId: str = Field(..., description="Typed <objectType>:<id>.")
    objectType: str = Field(..., description="Parsed object-type prefix (e.g. Interface).")


class TrailDetail(BaseModel):
    """getTrail — FROZEN (P1-G4 + P2-GAP-09). ``snapshotId`` is always present."""

    model_config = ConfigDict(extra="forbid")

    trailId: str
    domain: str
    snapshotId: str
    members: list[TrailMember]
    memberCount: int
    igpArea: str | None = None
    srlgGroup: str | None = None


class TrailSummary(BaseModel):
    """A listTrails item summary."""

    model_config = ConfigDict(extra="forbid")

    trailId: str
    domain: str
    memberCount: int
    igpArea: str | None = None
    srlgGroup: str | None = None


class ListTrailsResponse(BaseModel):
    """listTrails — the set of trail summaries for a snapshot+domain."""

    model_config = ConfigDict(extra="forbid")

    snapshotId: str
    domain: str
    count: int
    trails: list[TrailSummary]


class RebuildRequest(BaseModel):
    """POST /trails/rebuild — both fields required."""

    model_config = ConfigDict(extra="forbid")

    snapshotId: str
    domain: str


class TrailsBuiltSummary(BaseModel):
    """POST /trails/rebuild response — mirrors the TrailsBuiltEvent payload."""

    model_config = ConfigDict(extra="forbid")

    snapshotId: str
    domain: str
    trailIds: list[str]
    trailCount: int


class DependencyHealth(BaseModel):
    model_config = ConfigDict(extra="forbid")

    topology: str
    knowledge: str
    db: str
    kafka: str


class HealthResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: str
    dependencies: DependencyHealth
