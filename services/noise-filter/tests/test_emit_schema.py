"""TransactionEvent schema validity + typed alarms[] (AC-4, supporting alarms[] test)."""

from __future__ import annotations

import jsonschema
import numpy as np
import pytest

from noise_filter.emit import TransactionEmitter

from .fixtures import BASE_TIME, fiber_cut_cascade


def _emit_one(metrics=None):
    alarms = fiber_cut_cascade()
    labels = np.zeros(len(alarms), dtype=int)  # all one dense cluster
    emitter = TransactionEmitter(metrics=metrics)
    return alarms, emitter.build_events(
        alarms,
        labels,
        trail_id="t1",
        snapshot_id="snap-1",
        window_start=BASE_TIME,
        window_end=BASE_TIME,
        domain="core-ip",
    )


def test_transaction_event_validates_against_schema(transaction_event_schema, metrics):
    """AC-4: every emitted TransactionEvent validates against the frozen JSON Schema, with
    all six per-alarm fields present + correctly typed and alarmType mirrored verbatim."""
    alarms, result = _emit_one(metrics)
    assert len(result.events) == 1
    payload = result.events[0].to_dict()["payload"]

    jsonschema.validate(payload, transaction_event_schema)

    assert payload["alarmIds"]
    assert payload["alarms"]
    src_by_id = {a.alarmId: a for a in alarms}
    for entry in payload["alarms"]:
        for field in (
            "alarmId",
            "alarmType",
            "eventType",
            "raisedAt",
            "managedObjectId",
            "perceivedSeverity",
        ):
            assert field in entry and entry[field] not in (None, "")
        # alarmType is a verbatim pass-through mirror of the source AlarmEvent.alarmType.
        assert entry["alarmType"] == src_by_id[entry["alarmId"]].alarmType


def test_typed_alarms_array_populated_and_ordered(metrics):
    """Supporting (DA-13): alarms[] mirrors alarmIds[] 1:1 in the same order; each entry's six
    fields are round-tripped verbatim from the source AlarmEvent (alarmType not derived)."""
    alarms, result = _emit_one(metrics)
    payload = result.events[0].payload
    assert payload.alarmIds == [a.alarmId for a in payload.alarms]
    src_by_id = {a.alarmId: a for a in alarms}
    for entry in payload.alarms:
        src = src_by_id[entry.alarmId]
        assert entry.alarmType == src.alarmType
        assert entry.eventType == src.eventType
        assert entry.managedObjectId == src.managedObjectId
        assert entry.perceivedSeverity == src.perceivedSeverity
        assert entry.raisedAt == src.raisedAt
    # Ordered by raisedAt then alarmId (stable order for the Pattern Miner).
    raised = [a.raisedAt for a in payload.alarms]
    assert raised == sorted(raised)


def test_empty_cluster_never_published(metrics):
    """A TransactionEvent with empty alarmIds/alarms is a code bug and must never be published."""
    emitter = TransactionEmitter(metrics=metrics)
    from datetime import UTC, datetime

    from acp_event_model import TransactionEvent, TypedEnvelope

    bad = TypedEnvelope[TransactionEvent](
        eventId="e1",
        type="TransactionEvent",
        schemaVersion=1,
        occurredAt=datetime.now(UTC),
        source="noise-filter",
        traceId="t",
        payload=TransactionEvent(
            transactionId="x",
            trailId="t1",
            snapshotId="s1",
            alarmIds=[],
            alarms=[],
            windowStart=datetime.now(UTC),
            windowEnd=datetime.now(UTC),
        ),
    )
    with pytest.raises(ValueError):
        emitter._assert_valid(bad)
