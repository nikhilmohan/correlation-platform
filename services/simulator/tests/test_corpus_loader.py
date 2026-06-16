"""Ingest loader/validator tests (criteria 36-39).

Covers the ingest fail-fast paths not exercised by the corpus-model round-trip suite:
``load_snapshot`` (P1 ingest) validation against the canonical schema, and the
``load_corpus`` error branches (unreadable file, empty/header lines, missing envelope.payload).
Malformed input must raise ``IngestValidationError`` *before* any emission.
"""

from __future__ import annotations

import json

import networkx as nx
import pytest

from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine import snapshot_writer, topology_builder
from simulator.engine.domain_pack import TopologyParams
from simulator.ingest import corpus_loader
from simulator.ingest.corpus_loader import IngestValidationError


def _valid_snapshot(tmp_path) -> dict:
    import random

    pack = CoreIPPack()
    params = TopologyParams(node_count=12, site_count=3, interfaces_per_port=1, igp_area_count=2)
    graph: nx.DiGraph = topology_builder.build_topology(pack, params, random.Random(7)).graph
    return snapshot_writer.graph_to_snapshot(graph, pack.domain_id())


def test_ac36_load_snapshot_accepts_valid_file(tmp_path) -> None:
    snap = _valid_snapshot(tmp_path)
    path = tmp_path / "snap.json"
    path.write_text(json.dumps(snap))
    loaded = corpus_loader.load_snapshot(path)
    assert loaded["nodes"] == snap["nodes"]


def test_load_snapshot_unreadable_file_fails_fast() -> None:
    with pytest.raises(IngestValidationError, match="cannot read snapshot file"):
        corpus_loader.load_snapshot("/no/such/snapshot.json")


def test_load_snapshot_bad_json_fails_fast(tmp_path) -> None:
    path = tmp_path / "snap.json"
    path.write_text("{not json")
    with pytest.raises(IngestValidationError, match="cannot read snapshot file"):
        corpus_loader.load_snapshot(path)


def test_load_snapshot_schema_invalid_fails_fast(tmp_path) -> None:
    path = tmp_path / "snap.json"
    path.write_text(json.dumps({"domain": "core-ip", "nodes": "not-a-list"}))
    with pytest.raises(IngestValidationError, match="failed validation"):
        corpus_loader.load_snapshot(path)


def test_load_corpus_unreadable_file_fails_fast() -> None:
    with pytest.raises(IngestValidationError, match="cannot read corpus file"):
        corpus_loader.load_corpus("/no/such/corpus.jsonl")


def test_load_corpus_skips_blank_lines_and_header(tmp_path) -> None:
    payload = {
        "alarmId": "a1",
        "managedObjectId": "Node:n1",
        "eventType": "communicationsAlarm",
        "probableCause": "nodeFailure",
        "alarmType": "NodeDown",
        "perceivedSeverity": "critical",
        "raisedAt": "2026-01-01T00:00:00Z",
        "state": "raised",
        "trailIds": [],
    }
    record = {"seq": 0, "topic": "alarms.history", "envelope": {"payload": payload}}
    lines = [
        json.dumps({"corpusVersion": 1, "count": 1}),  # header
        "",  # blank
        json.dumps(record),
        "   ",  # whitespace
    ]
    path = tmp_path / "corpus.jsonl"
    path.write_text("\n".join(lines))
    events = corpus_loader.load_corpus(path)
    assert len(events) == 1
    assert events[0].alarmId == "a1"


def test_load_corpus_missing_envelope_payload_fails_fast(tmp_path) -> None:
    path = tmp_path / "corpus.jsonl"
    path.write_text(json.dumps({"seq": 0, "topic": "alarms.history", "envelope": {}}))
    with pytest.raises(IngestValidationError, match="missing envelope.payload"):
        corpus_loader.load_corpus(path)


def test_load_corpus_orders_by_seq(tmp_path) -> None:
    def rec(seq: int, alarm_id: str) -> dict:
        return {
            "seq": seq,
            "topic": "alarms.history",
            "envelope": {
                "payload": {
                    "alarmId": alarm_id,
                    "managedObjectId": "Node:n1",
                    "eventType": "communicationsAlarm",
                    "probableCause": "nodeFailure",
                    "alarmType": "NodeDown",
                    "perceivedSeverity": "critical",
                    "raisedAt": "2026-01-01T00:00:00Z",
                    "state": "raised",
                    "trailIds": [],
                }
            },
        }

    path = tmp_path / "corpus.jsonl"
    path.write_text("\n".join([json.dumps(rec(2, "second")), json.dumps(rec(1, "first"))]))
    events = corpus_loader.load_corpus(path)
    assert [e.alarmId for e in events] == ["first", "second"]
