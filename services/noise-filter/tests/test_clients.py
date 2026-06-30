"""TopologyClient + TrailBuilderClient parsing (respx) and kafka_io smoke tests."""

from __future__ import annotations

import httpx
import respx

from noise_filter.clients import TopologyClient, TrailBuilderClient

TOPO = "http://topology.test"
TB = "http://trail-builder.test"


@respx.mock
def test_topology_client_returns_attributes_map():
    respx.get(f"{TOPO}/topology/nodes/Port:a").mock(
        return_value=httpx.Response(
            200,
            json={
                "managedObjectId": "Port:a",
                "objectType": "Port",
                "domain": "core-ip",
                "name": "a",
                "attributes": {"equipmentType": "router", "vendor": "acme"},
                "snapshotId": "s1",
            },
        )
    )
    attrs = TopologyClient(TOPO).fetch_attributes("Port:a")
    assert attrs == {"equipmentType": "router", "vendor": "acme"}


@respx.mock
def test_topology_client_missing_attributes_returns_empty():
    respx.get(f"{TOPO}/topology/nodes/Port:b").mock(
        return_value=httpx.Response(200, json={"managedObjectId": "Port:b"})
    )
    assert TopologyClient(TOPO).fetch_attributes("Port:b") == {}


@respx.mock
def test_trail_builder_client_parses_members_edges_seed_snapshot():
    respx.get(f"{TB}/trails/t1").mock(
        return_value=httpx.Response(
            200,
            json={
                "trailId": "t1",
                "snapshotId": "snap-9",
                "domain": "core-ip",
                "seed": "FiberSpan:f1",
                "members": [
                    {"managedObjectId": "FiberSpan:f1"},
                    {"managedObjectId": "IPLink:l1"},
                ],
                "edges": [{"from": "FiberSpan:f1", "to": "IPLink:l1"}],
            },
        )
    )
    ctx = TrailBuilderClient(TB).get_trail("t1")
    assert ctx.snapshot_id == "snap-9"
    assert ctx.domain == "core-ip"
    assert ctx.seed_id == "FiberSpan:f1"
    assert ctx.member_ids == ["FiberSpan:f1", "IPLink:l1"]
    assert ctx.edges == [("FiberSpan:f1", "IPLink:l1")]


@respx.mock
def test_trail_builder_client_handles_alt_edge_key_names():
    respx.get(f"{TB}/trails/t2").mock(
        return_value=httpx.Response(
            200,
            json={
                "snapshotId": "s2",
                "members": [{"managedObjectId": "A:1"}, {"managedObjectId": "B:2"}],
                "edges": [{"source": "A:1", "target": "B:2"}],
                "root": "A:1",
            },
        )
    )
    ctx = TrailBuilderClient(TB).get_trail("t2")
    assert ctx.edges == [("A:1", "B:2")]
    assert ctx.seed_id == "A:1"


def test_kafka_producer_publishes_serialized_envelope(monkeypatch):
    """kafka_io.TransactionProducer.publish serializes the envelope and produces it."""
    produced = {}

    class FakeKafkaProducer:
        def __init__(self, conf):
            produced["conf"] = conf

        def produce(self, topic, value=None, headers=None):
            produced["topic"] = topic
            produced["value"] = value
            produced["headers"] = headers

        def poll(self, t):
            pass

        def flush(self, t):
            produced["flushed"] = True

    import confluent_kafka

    monkeypatch.setattr(confluent_kafka, "Producer", FakeKafkaProducer)

    from datetime import UTC, datetime

    from acp_event_model import Alarm, TransactionEvent, TypedEnvelope

    from noise_filter.kafka_io import TransactionProducer

    env = TypedEnvelope[TransactionEvent](
        eventId="11111111-1111-1111-1111-111111111111",
        type="TransactionEvent",
        schemaVersion=1,
        occurredAt=datetime.now(UTC),
        source="noise-filter",
        traceId="tr",
        payload=TransactionEvent(
            transactionId="x",
            trailId="t1",
            snapshotId="s1",
            alarmIds=["a1"],
            alarms=[
                Alarm(
                    alarmId="a1",
                    alarmType="PortDown",
                    eventType="communicationsAlarm",
                    raisedAt=datetime.now(UTC),
                    managedObjectId="Port:1",
                    perceivedSeverity="major",
                )
            ],
            windowStart=datetime.now(UTC),
            windowEnd=datetime.now(UTC),
        ),
    )
    p = TransactionProducer("localhost:9092")
    assert produced["conf"]["enable.idempotence"] is True
    p.publish("transactions.clean", env)
    assert produced["topic"] == "transactions.clean"
    assert b"TransactionEvent" in produced["value"]
    p.produce("alarms.enriched.dlq", value=b"raw", headers=[("reason", b"x")])
    assert produced["topic"] == "alarms.enriched.dlq"
    p.flush()
    assert produced["flushed"] is True


def test_kafka_make_consumer(monkeypatch):
    subscribed = {}

    class FakeConsumer:
        def __init__(self, conf):
            subscribed["conf"] = conf

        def subscribe(self, topics):
            subscribed["topics"] = topics

    import confluent_kafka

    monkeypatch.setattr(confluent_kafka, "Consumer", FakeConsumer)
    from noise_filter.kafka_io import make_consumer

    make_consumer("localhost:9092", "noise-filter-grp", ["alarms.enriched"])
    assert subscribed["conf"]["enable.auto.commit"] is False
    assert subscribed["topics"] == ["alarms.enriched"]
