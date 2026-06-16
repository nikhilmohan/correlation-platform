"""``trails.built`` consumer + DLQ tests (spec criteria 6, 9, 11, 12).

Drives :class:`codebook_generator.consumer.TrailsBuiltHandler` end-to-end against the wired
``components`` (SQLite store + respx-mocked collaborators + FakeProducer). Covers idempotent
dedup on ``eventId``, unknown ``schemaVersion`` rejection, malformed-message DLQ routing, and
the frozen ``CodebookGeneratedEvent`` emission shape.
"""

from __future__ import annotations

import json

from acp_event_model import CodebookGeneratedEvent, TrailsBuiltEvent, deserialize

from codebook_generator.bootstrap import Components

from .conftest import make_trails_built_envelope, trails_built_bytes


def _generated_payloads(producer) -> list[CodebookGeneratedEvent]:  # noqa: ANN001
    payloads = []
    for raw in producer.topic_messages("codebook.generated"):
        env = deserialize(raw)
        payloads.append(env.payload)
    return payloads


def test_consume_compiles_and_emits_generated_event(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """A valid trails.built compiles a codebook and emits exactly one codebook.generated."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    assert result is not None
    assert result.scenario_count > 0
    emitted = _generated_payloads(fake_producer)
    assert len(emitted) == 1
    assert isinstance(emitted[0], CodebookGeneratedEvent)


def test_duplicate_event_id_is_deduplicated(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """AC-6: the same eventId delivered twice compiles once (second emit re-uses prior id)."""
    raw = trails_built_bytes(
        snapshot_id="snap-X",
        domain="core-ip",
        event_id="11111111-1111-1111-1111-111111111111",
    )
    first = components.handler.handle(raw)
    second = components.handler.handle(raw)

    assert second is not None and second.deduped is True
    # Exactly one codebook compiled (same codebookId on both results).
    assert first.codebook_id == second.codebook_id
    # Only one distinct codebook row exists for the key.
    assert components.store.get_active("core-ip", "snap-X")["codebook_id"] == first.codebook_id


def test_distinct_snapshots_produce_distinct_codebooks(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """AC-5: two snapshots -> two codebooks, two generated events with correct snapshotIds."""
    a = components.handler.handle(trails_built_bytes(snapshot_id="snap-A", domain="core-ip"))
    b = components.handler.handle(trails_built_bytes(snapshot_id="snap-B", domain="core-ip"))
    assert a.codebook_id != b.codebook_id

    payloads = _generated_payloads(fake_producer)
    by_snapshot = {p.snapshotId: p for p in payloads}
    assert by_snapshot["snap-A"].codebookId == a.codebook_id
    assert by_snapshot["snap-B"].codebookId == b.codebook_id


def test_generated_event_carries_domain_and_matches_scenario_count(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """AC-9: emitted CodebookGeneratedEvent carries domain + matching scenarioCount."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    [payload] = _generated_payloads(fake_producer)
    assert payload.domain == "core-ip"
    assert payload.snapshotId == "snap-X"
    assert payload.codebookId == result.codebook_id
    assert payload.scenarioCount == result.scenario_count
    # scenarioCount equals the number of persisted scenarios.
    assert payload.scenarioCount == len(components.store.get_scenarios(result.codebook_id))


def test_unknown_schema_version_routes_to_dlq(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """AC-11: schemaVersion >= 2 is rejected and routed to trails.built.dlq; no crash."""
    raw = trails_built_bytes(snapshot_id="snap-X", domain="core-ip", schema_version=2)
    result = components.handler.handle(raw)
    assert result is None
    assert fake_producer.topic_messages("trails.built.dlq") == [raw]
    # No codebook.generated emitted for the rejected message.
    assert fake_producer.topic_messages("codebook.generated") == []


def test_malformed_message_missing_snapshot_routes_to_dlq(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """AC-12: a message missing snapshotId is routed to the DLQ; loop continues."""
    envelope = make_trails_built_envelope(snapshot_id="snap-X", domain="core-ip")
    # Drop snapshotId from the payload to make it unprocessable.
    envelope["payload"].pop("snapshotId", None)
    bad = json.dumps(envelope).encode("utf-8")

    bad_result = components.handler.handle(bad)
    assert bad_result is None
    assert len(fake_producer.topic_messages("trails.built.dlq")) == 1

    # A subsequent valid message still processes (consumer not wedged).
    good = components.handler.handle(trails_built_bytes(snapshot_id="snap-Y", domain="core-ip"))
    assert good is not None and good.scenario_count > 0


def test_garbage_bytes_route_to_dlq(components: Components, fake_producer) -> None:  # noqa: ANN001
    """Non-JSON / undeserializable bytes are routed to the DLQ, not raised."""
    result = components.handler.handle(b"not-json-at-all")
    assert result is None
    assert len(fake_producer.topic_messages("trails.built.dlq")) == 1


def test_wrong_event_type_routes_to_dlq(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """A correctly-framed envelope of the wrong type is routed to the DLQ."""
    # Build a valid envelope then mislabel the type so the type guard trips.
    envelope = make_trails_built_envelope(snapshot_id="snap-X", domain="core-ip")
    # Round-trip through the binding to ensure it deserializes, then mislabel.
    payload = TrailsBuiltEvent(**envelope["payload"])
    assert payload.snapshotId == "snap-X"  # sanity
    envelope["type"] = "TrailsBuiltEvent"  # keep deserializable
    # Force a type mismatch post-deserialize by patching the handler's type check path:
    # easiest faithful route is a payload the codec maps to another known type. Instead we
    # assert the explicit guard via a hand-built envelope with an unknown type.
    envelope["type"] = "SomethingElseEvent"
    raw = json.dumps(envelope).encode("utf-8")
    result = components.handler.handle(raw)
    assert result is None
    assert len(fake_producer.topic_messages("trails.built.dlq")) == 1


def test_default_domain_applied_when_event_domain_absent(
    components: Components, fake_producer
) -> None:  # noqa: ANN001
    """A pre-#90 event without domain defaults to the configured MVP domain (core-ip)."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-D", domain=None))
    assert result is not None
    assert result.domain == "core-ip"
    [payload] = _generated_payloads(fake_producer)
    assert payload.domain == "core-ip"
