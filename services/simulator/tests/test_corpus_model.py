"""Alarm-corpus + label-store data-model tests (spec AC 10a, 28, 30 model layer).

These exercise the pure data-model layer of the corpus + label artifacts: the corpus writer's
serialized records reconstruct verbatim through the loader against the frozen AlarmEvent binding,
a malformed/alarmType-missing line fails fast, and the ground-truth label JSONL round-trips in
the frozen retrieval shape. (CLI / Kafka replay wiring is the next agent's scope.)
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

from simulator.engine.labels import LabelStore, label_from_dict, label_to_dict
from simulator.engine.models import GroundTruthLabel, SynthAlarm
from simulator.engine.replay import synth_to_event, wrap_envelope
from simulator.ingest.corpus_loader import IngestValidationError, load_corpus
from simulator.ingest.corpus_writer import CorpusWriter

_START = datetime(2026, 1, 1, tzinfo=UTC)


def _synth(i: int, alarm_type: str = "FiberFault", moid: str = "FiberSpan:F-1") -> SynthAlarm:
    return SynthAlarm(
        alarm_id=f"ALM-{i:07d}",
        managed_object_id=moid,
        alarm_type=alarm_type,
        event_type="communicationsAlarm",
        probable_cause="lossOfSignal",
        perceived_severity="critical",
        raised_at=_START,
        scenario_id="sc-fiber-cut-000",
    )


# --- corpus model: write -> load reconstructs verbatim (AC 28 / AC 30 model layer) ---------


def test_corpus_write_then_load_reconstructs_events_verbatim(tmp_path: Path) -> None:
    """A written corpus reloads through the frozen AlarmEvent binding preserving payload+order."""
    path = tmp_path / "corpus.jsonl"
    synths = [_synth(1, "FiberFault"), _synth(2, "LinkDown", "IPLink:N0_N1")]
    with CorpusWriter(path, source_run_id="run-1", phase="p2", topic="alarms.history") as cw:
        for s in synths:
            event = synth_to_event(s)
            env = wrap_envelope(event, trace_id=s.trace_id)
            cw.tap("alarms.history", env)
        assert cw.count == 2

    events = load_corpus(path)
    assert len(events) == len(synths)
    # order + payload identity preserved (alarmId / alarmType / moid / raisedAt)
    for original, loaded in zip(synths, events, strict=True):
        assert loaded.alarmId == original.alarm_id
        assert loaded.alarmType == original.alarm_type
        assert loaded.managedObjectId == original.managed_object_id
        assert loaded.raisedAt == original.raised_at


def test_corpus_header_line_is_skipped_on_load(tmp_path: Path) -> None:
    """The corpus header line (corpusVersion/count) is not parsed as an AlarmEvent."""
    path = tmp_path / "corpus.jsonl"
    with CorpusWriter(path, source_run_id="run-1", phase="p2", topic="alarms.history") as cw:
        cw.tap("alarms.history", wrap_envelope(synth_to_event(_synth(1)), trace_id="t"))
    events = load_corpus(path)
    assert len(events) == 1  # header skipped, one record


def test_corpus_missing_alarm_type_fails_fast(tmp_path: Path) -> None:
    """AC28 model: a corpus line whose payload omits alarmType aborts the load (fail-fast)."""
    path = tmp_path / "corpus.jsonl"
    with CorpusWriter(path, source_run_id="run-1", phase="p2", topic="alarms.history") as cw:
        cw.tap("alarms.history", wrap_envelope(synth_to_event(_synth(1)), trace_id="t"))
    # corrupt the record: drop alarmType from the payload
    lines = path.read_text().splitlines()
    import json

    rec = json.loads(lines[1])
    rec["envelope"]["payload"].pop("alarmType")
    lines[1] = json.dumps(rec)
    path.write_text("\n".join(lines))
    with pytest.raises(IngestValidationError):
        load_corpus(path)


def test_corpus_bad_json_line_fails_fast(tmp_path: Path) -> None:
    """AC28 model: a malformed (non-JSON) corpus line fails fast."""
    path = tmp_path / "corpus.jsonl"
    path.write_text('{"corpusVersion": 1, "count": 1}\nNOT JSON\n')
    with pytest.raises(IngestValidationError):
        load_corpus(path)


# --- label store: frozen retrieval shape + JSONL round-trip (AC 10a) -----------------------


def test_label_to_dict_uses_frozen_retrieval_shape() -> None:
    """AC10a: label_to_dict emits the frozen {scenarioId..rootCauseAlarmType..children} keys."""
    label = GroundTruthLabel(
        scenario_id="sc-fiber-cut-000",
        scenario_type="fiber-cut",
        root_cause="ALM-0000001",
        root_cause_managed_object_id="FiberSpan:F-1",
        root_cause_alarm_type="FiberFault",
        children=["ALM-0000002", "ALM-0000003"],
    )
    d = label_to_dict(label)
    assert set(d.keys()) == {
        "scenarioId",
        "scenarioType",
        "rootCause",
        "rootCauseManagedObjectId",
        "rootCauseAlarmType",
        "children",
    }
    assert d["rootCauseAlarmType"] == "FiberFault"


def test_label_store_jsonl_round_trip(tmp_path: Path) -> None:
    """AC10a: a label store exports + reloads identically (the oracle ingest path)."""
    store = LabelStore()
    store.record(
        GroundTruthLabel(
            "sc-fiber-cut-000",
            "fiber-cut",
            "ALM-0000001",
            "FiberSpan:F-1",
            "FiberFault",
            ["ALM-0000002"],
        )
    )
    store.record(
        GroundTruthLabel(
            "sc-port-fault-000",
            "port-fault",
            "ALM-0000010",
            "Port:N0-LC1-P1",
            "PortDown",
            ["ALM-0000011"],
        )
    )
    path = tmp_path / "labels.jsonl"
    store.export_to_file(path)

    reloaded = LabelStore()
    reloaded.load_from_file(path)
    assert [label_to_dict(x) for x in reloaded.all()] == [label_to_dict(x) for x in store.all()]
    assert reloaded.distinct_scenario_types() == {"fiber-cut", "port-fault"}


def test_label_from_dict_parses_frozen_shape() -> None:
    """AC10a: label_from_dict reconstructs a GroundTruthLabel from the frozen JSONL shape."""
    obj = {
        "scenarioId": "sc-x-000",
        "scenarioType": "x",
        "rootCause": "ALM-1",
        "rootCauseManagedObjectId": "Node:N0",
        "rootCauseAlarmType": "LOS",
        "children": ["ALM-2"],
    }
    label = label_from_dict(obj)
    assert label.root_cause_alarm_type == "LOS"
    assert label.children == ["ALM-2"]


def test_label_store_malformed_file_fails_fast(tmp_path: Path) -> None:
    """A malformed labels line raises a clear ValueError (oracle ingest fail-fast)."""
    path = tmp_path / "labels.jsonl"
    path.write_text('{"scenarioId": "x"}\n')  # missing required keys
    store = LabelStore()
    with pytest.raises(ValueError):
        store.load_from_file(path)
