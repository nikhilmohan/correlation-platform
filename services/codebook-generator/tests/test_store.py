"""Codebook Store tests (SQLite + ATTACH 'codebook' schema).

Exercises :class:`codebook_generator.store.CodebookStore` directly against the in-memory
SQLite engine with the ``codebook`` schema attached and the partial-unique one-active index
created portably (see ``conftest.engine``). Covers the atomic supersede-then-insert writer,
the one-active-per-``(domain, snapshotId)`` invariant (spec criteria 18-20), idempotent
``eventId`` dedup (criterion 6), and every read accessor backing the API.
"""

from __future__ import annotations

import pytest
from sqlalchemy.exc import IntegrityError

from codebook_generator.models import PredictedSymptom, Scenario
from codebook_generator.store import CodebookStore, new_codebook_id, scenario_id


def _scenario(origin: str, origin_type: str, symptoms: list[tuple[str, str]]) -> Scenario:
    return Scenario(
        scenarioId=origin,  # provisional; store rewrites to codebook-scoped id
        faultOriginObjectId=origin,
        faultOriginType=origin_type,
        predictedSymptoms=[PredictedSymptom(alarmType=a, managedObjectId=o) for a, o in symptoms],
        trailIds=["TRAIL-1"],
    )


# FiberSpan origin token is `FiberCut` (live core-ip seed taxonomy, #262).
_FIBER = _scenario(
    "FiberSpan:f1",
    "FiberSpan",
    [("FiberCut", "FiberSpan:f1"), ("LinkDown", "IPLink:l1")],
)


def test_ping_returns_true_for_live_engine(store: CodebookStore) -> None:
    """Readiness ping succeeds against a live engine."""
    assert store.ping() is True


def test_persist_then_read_meta_and_scenarios(store: CodebookStore) -> None:
    """persist_codebook writes the codebook + scenarios and sets it active."""
    cb = store.persist_codebook(
        event_id="evt-1", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    meta = store.get_codebook_meta(cb)
    assert meta is not None
    assert meta["domain"] == "core-ip"
    assert meta["snapshot_id"] == "snap-X"
    assert meta["scenario_count"] == 1
    assert meta["active"] is True

    scenarios = store.get_scenarios(cb)
    assert len(scenarios) == 1
    # Store rewrites the scenarioId to a codebook-scoped id.
    assert scenarios[0].scenarioId == scenario_id(cb, "FiberSpan:f1")
    assert scenarios[0].faultOriginType == "FiberSpan"
    assert scenarios[0].predictedSymptoms[0].alarmType == "FiberCut"
    assert scenarios[0].trailIds == ["TRAIL-1"]


def test_get_scenario_by_id_and_filter_by_type(store: CodebookStore) -> None:
    """get_scenario resolves a single scenario; get_scenarios filters by fault-origin type."""
    iface = _scenario("Interface:i1", "Interface", [("InterfaceDown", "Interface:i1")])
    cb = store.persist_codebook(
        event_id="evt-2", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER, iface]
    )
    sid = scenario_id(cb, "Interface:i1")
    one = store.get_scenario(cb, sid)
    assert one is not None
    assert one.faultOriginType == "Interface"

    only_fiber = store.get_scenarios(cb, fault_origin_type="FiberSpan")
    assert [s.faultOriginType for s in only_fiber] == ["FiberSpan"]

    assert store.get_scenario(cb, "no-such-scenario") is None


def test_get_full_codebook_assembles_meta_plus_scenarios(store: CodebookStore) -> None:
    """get_full_codebook returns metadata + scenarios; None for unknown id."""
    cb = store.persist_codebook(
        event_id="evt-3", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    full = store.get_full_codebook(cb)
    assert full is not None
    assert full.codebookId == cb
    assert full.scenarioCount == 1
    assert len(full.scenarios) == 1
    assert store.get_full_codebook("missing") is None


def test_already_processed_distinguishes_unseen_codebook_and_no_codebook(
    store: CodebookStore,
) -> None:
    """already_processed: None=unseen, codebook_id=compiled, ''=processed-no-codebook."""
    assert store.already_processed("evt-x") is None
    cb = store.persist_codebook(
        event_id="evt-x", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    assert store.already_processed("evt-x") == cb

    store.record_processed_no_codebook("evt-dlq")
    assert store.already_processed("evt-dlq") == ""


def test_one_active_invariant_supersedes_prior_active(store: CodebookStore) -> None:
    """SUPERSEDE: recompiling a key demotes the prior active codebook (spec criterion 19)."""
    old = store.persist_codebook(
        event_id="evt-old", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    new = store.persist_codebook(
        event_id="evt-new", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    assert old != new
    # Exactly one active, and it is the new one (DETERMINISTIC-RETRIEVAL, criterion 20).
    active = store.get_active("core-ip", "snap-X")
    assert active is not None
    assert active["codebook_id"] == new
    # Prior codebook content is preserved and still retrievable by its id.
    assert store.get_codebook_meta(old) is not None
    assert store.get_codebook_meta(old)["active"] is False


def test_partial_unique_index_rejects_two_actives_for_same_key(store: CodebookStore) -> None:
    """The store-level partial-unique index enforces one active per (domain, snapshotId)."""
    store.persist_codebook(
        event_id="evt-a", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    # Directly inserting a second active row for the same key violates the index.
    from datetime import UTC, datetime

    from codebook_generator.store import codebooks_table

    with pytest.raises(IntegrityError):
        with store.engine.begin() as conn:
            conn.execute(
                codebooks_table.insert().values(
                    codebook_id=new_codebook_id(),
                    snapshot_id="snap-X",
                    domain="core-ip",
                    active=True,
                    scenario_count=0,
                    knowledge_version=None,
                    compiled_at=datetime.now(UTC),
                )
            )


def test_distinct_snapshots_yield_distinct_active_codebooks(store: CodebookStore) -> None:
    """Different snapshots each keep their own active codebook (no cross-supersede)."""
    a = store.persist_codebook(
        event_id="evt-a", snapshot_id="snap-A", domain="core-ip", scenarios=[_FIBER]
    )
    b = store.persist_codebook(
        event_id="evt-b", snapshot_id="snap-B", domain="core-ip", scenarios=[_FIBER]
    )
    assert store.get_active("core-ip", "snap-A")["codebook_id"] == a
    assert store.get_active("core-ip", "snap-B")["codebook_id"] == b


def test_list_by_domain_and_snapshot(store: CodebookStore) -> None:
    """list_by_domain filters to the domain; list_by_snapshot to the snapshot."""
    store.persist_codebook(
        event_id="evt-ci", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    store.persist_codebook(
        event_id="evt-tr", snapshot_id="snap-Y", domain="transport", scenarios=[_FIBER]
    )
    core = store.list_by_domain("core-ip")
    assert [m["domain"] for m in core] == ["core-ip"]
    assert store.list_by_domain("transport")[0]["domain"] == "transport"
    assert [m["snapshot_id"] for m in store.list_by_snapshot("snap-X")] == ["snap-X"]


def test_get_active_returns_none_when_absent(store: CodebookStore) -> None:
    """An unknown (domain, snapshotId) key has no active codebook."""
    assert store.get_active("core-ip", "never") is None


def test_clear_removes_all_rows(store: CodebookStore) -> None:
    """The clear() maintenance helper empties the store respecting FK order."""
    cb = store.persist_codebook(
        event_id="evt-1", snapshot_id="snap-X", domain="core-ip", scenarios=[_FIBER]
    )
    store.clear()
    assert store.get_codebook_meta(cb) is None
    assert store.get_scenarios(cb) == []
    assert store.already_processed("evt-1") is None


def test_persist_empty_scenarios_is_allowed(store: CodebookStore) -> None:
    """A codebook with zero scenarios persists with scenario_count 0."""
    cb = store.persist_codebook(
        event_id="evt-empty", snapshot_id="snap-Z", domain="core-ip", scenarios=[]
    )
    assert store.get_codebook_meta(cb)["scenario_count"] == 0
    assert store.get_scenarios(cb) == []
