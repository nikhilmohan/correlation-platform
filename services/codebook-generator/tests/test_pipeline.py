"""Compilation-pipeline orchestration tests (spec criteria 4, 7, 10, 14, 15, 17).

Drives :class:`codebook_generator.pipeline.CompilationPipeline` through the wired
``components`` against respx-mocked collaborators, asserting the domain-scoped contract:
fault-origin + template fetches carry the domain, Topology enumeration carries
``snapshotId`` + ``domain``, no Topology snapshot-metadata lookup happens for domain
resolution, every scenario is trail-tagged, and a second domain (transport) compiles with no
code change. Also covers the vocabulary-failure -> DLQ path.
"""

from __future__ import annotations

import httpx
import pytest

from codebook_generator.bootstrap import Components, build_components
from codebook_generator.config import ConfigError
from codebook_generator.consumer import DlqRouter, TrailsBuiltHandler

from .conftest import (
    FakeProducer,
    MockCollaborators,
    make_settings,
    trails_built_bytes,
)


def test_full_compile_cycle_uses_only_mocked_collaborators(
    components: Components, mocks: MockCollaborators
) -> None:
    """AC-7: a full compile completes against MOCK collaborators (no real HTTP)."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    assert result is not None and result.scenario_count == 4
    # Every outbound call went through the mock router (recorded).
    assert mocks.calls_to("/fault-origin-types")
    assert mocks.calls_to("/propagation-templates")
    assert mocks.calls_to("/alarm-type-vocabulary")
    assert mocks.calls_to("/topology/nodes")
    assert mocks.calls_to("/topology/traversal")
    assert mocks.calls_to("/trails/by-object")


def test_knowledge_calls_carry_the_domain_parameter(
    components: Components, mocks: MockCollaborators
) -> None:
    """AC-14: Knowledge fault-origin + template fetches are scoped by domain in the path."""
    components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    fo_calls = mocks.calls_to("/fault-origin-types")
    pt_calls = mocks.calls_to("/propagation-templates")
    assert all("/domains/core-ip/" in r.url.path for r in fo_calls)
    assert all("/domains/core-ip/" in r.url.path for r in pt_calls)


def test_topology_enumeration_carries_snapshot_and_domain(
    components: Components, mocks: MockCollaborators
) -> None:
    """AC-15: the Topology nodes query carries both snapshotId and domain."""
    components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    node_calls = mocks.calls_to("/topology/nodes")
    assert node_calls
    for r in node_calls:
        assert r.url.params["domain"] == "core-ip"
        assert r.url.params["snapshotId"]  # snapshot-scoped (current snapshot for the domain)


def test_domain_resolution_makes_no_snapshot_metadata_call(
    components: Components, mocks: MockCollaborators
) -> None:
    """AC-10: domain read from the event -> zero calls to a topology snapshot-metadata path."""
    components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    # No call to any snapshot-metadata resolution endpoint on Topology.
    assert mocks.calls_to("/snapshots") == []
    assert mocks.calls_to("/snapshot-metadata") == []


def test_every_scenario_is_tagged_to_at_least_one_trail(components: Components) -> None:
    """AC-4: every compiled scenario has a non-empty trailIds."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    scenarios = components.store.get_scenarios(result.codebook_id)
    assert scenarios
    assert all(s.trailIds for s in scenarios)


def test_second_domain_compiles_without_code_change(
    settings, store, fake_producer: FakeProducer, mocks: MockCollaborators
) -> None:  # noqa: ANN001
    """AC-17: the transport domain compiles using its own fault-origins/templates."""
    components = build_components(
        settings, message_producer=fake_producer, store=store, http_client=mocks.httpx_client()
    )
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-T", domain="transport"))
    assert result is not None
    assert result.domain == "transport"
    meta = components.store.get_codebook_meta(result.codebook_id)
    assert meta["domain"] == "transport"
    # Compiled from the transport-specific fault-origin (OpticalAmp) and FEEDS cascade.
    scenarios = components.store.get_scenarios(result.codebook_id)
    assert [s.faultOriginType for s in scenarios] == ["OpticalAmp"]
    alarms = [sym.alarmType for sym in scenarios[0].predictedSymptoms]
    assert alarms == ["AmpFault", "ChannelLoss"]
    # Knowledge calls were scoped to the transport domain.
    assert all("/domains/transport/" in r.url.path for r in mocks.calls_to("/fault-origin-types"))


def test_duplicate_event_does_not_recompile(
    components: Components, mocks: MockCollaborators
) -> None:
    """AC-6 (pipeline view): a deduped eventId re-emits without re-querying collaborators."""
    raw = trails_built_bytes(
        snapshot_id="snap-X",
        domain="core-ip",
        event_id="22222222-2222-2222-2222-222222222222",
    )
    components.handler.handle(raw)
    calls_after_first = len(mocks.requests)
    components.handler.handle(raw)
    # No additional collaborator calls on the deduped re-delivery.
    assert len(mocks.requests) == calls_after_first


def test_vocabulary_violation_routes_trigger_to_dlq(
    settings, store, fake_producer: FakeProducer
) -> None:  # noqa: ANN001
    """A symptom token outside the domain vocabulary fails the compile and DLQs the trigger."""

    # A mock router whose templates emit a token absent from the vocabulary.
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/fault-origin-types"):
            # Frozen Knowledge RecordResponse envelope — domain fields live under .payload (#224).
            return httpx.Response(
                200,
                json={
                    "records": [
                        {
                            "domain": "core-ip",
                            "recordType": "faultOriginType",
                            "recordId": "fo-0",
                            "payload": {
                                "objectType": "FiberSpan",
                                "originAlarmType": "FiberFault",
                            },
                        }
                    ]
                },
            )
        if path.endswith("/propagation-templates"):
            return httpx.Response(
                200,
                json={
                    "records": [
                        {
                            "domain": "core-ip",
                            "recordType": "propagationTemplate",
                            "recordId": "pt-0",
                            "payload": {
                                "edgeType": "RIDES_ON",
                                "trigger": {"objectType": "FiberSpan", "alarmType": "FiberFault"},
                                "effect": {"objectType": "IPLink", "alarmType": "BogusAlarm"},
                            },
                        }
                    ]
                },
            )
        if path.endswith("/alarm-type-vocabulary"):
            return httpx.Response(200, json={"alarmTypes": ["FiberFault"]})  # no BogusAlarm
        if path == "/topology/nodes":
            return httpx.Response(
                200,
                json={
                    "domain": "core-ip",
                    "objectType": "FiberSpan",
                    "count": 1,
                    "nodes": [
                        {
                            "managedObjectId": "FiberSpan:f1",
                            "objectType": "FiberSpan",
                            "domain": "core-ip",
                        }
                    ],
                },
            )
        if path == "/topology/traversal":
            return httpx.Response(
                200,
                json={
                    "start": "FiberSpan:f1",
                    "domain": "core-ip",
                    "reached": [
                        {
                            "managedObjectId": "IPLink:l1",
                            "objectType": "IPLink",
                            "domain": "core-ip",
                        }
                    ],
                    "edges": [
                        {"source": "FiberSpan:f1", "target": "IPLink:l1", "relation": "RIDES_ON"}
                    ],
                },
            )
        if path == "/trails/by-object":
            return httpx.Response(
                200,
                json={
                    "managedObjectId": request.url.params["managedObjectId"],
                    "domain": "core-ip",
                    "trailIds": ["TRAIL-1"],
                },
            )
        return httpx.Response(404)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    components = build_components(
        settings, message_producer=fake_producer, store=store, http_client=client
    )
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    assert result is None
    assert len(fake_producer.topic_messages("trails.built.dlq")) == 1
    # No codebook persisted for the failed compile.
    assert components.store.get_active("core-ip", "snap-X") is None


def test_integration_failure_routes_trigger_to_dlq(
    settings, store, fake_producer: FakeProducer
) -> None:  # noqa: ANN001
    """An unrecoverable integration-point error routes the trigger to the DLQ."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    components = build_components(
        settings, message_producer=fake_producer, store=store, http_client=client
    )
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    assert result is None
    assert len(fake_producer.topic_messages("trails.built.dlq")) == 1


def test_build_components_requires_all_integration_urls(store) -> None:  # noqa: ANN001
    """AC-7: an unset integration-point URL makes the service refuse to start."""
    bad = make_settings(KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL="")
    with pytest.raises(ConfigError) as exc:
        build_components(bad, message_producer=FakeProducer(), store=store)
    assert "KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL" in str(exc.value)


def test_build_components_requires_database_url() -> None:
    """AC-7 (datastore): an unset DATABASE_URL makes the service refuse to start."""
    bad = make_settings(DATABASE_URL="")
    with pytest.raises(ConfigError) as exc:
        build_components(bad, message_producer=FakeProducer())
    assert "DATABASE_URL" in str(exc.value)


def test_dlq_router_never_raises_into_loop() -> None:
    """The DlqRouter swallows producer failures so the consumer loop is never wedged."""
    fake = FakeProducer()
    fake.fail_topics.add("trails.built.dlq")
    router = DlqRouter(fake, "trails.built.dlq")
    # Must not raise even though the underlying produce fails.
    router.route(b"raw", "some reason")


def test_handler_default_domain_falls_back(components: Components) -> None:
    """The handler resolves the configured default domain when the event omits it."""
    assert isinstance(components.handler, TrailsBuiltHandler)
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-D", domain=None))
    assert result.domain == "core-ip"
