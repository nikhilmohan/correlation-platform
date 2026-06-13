"""Shared pytest fixtures.

The Codebook Store is exercised against an in-memory SQLite database with a ``codebook``
schema attached (so the schema-qualified Table metadata resolves identically to Postgres),
and the partial-unique one-active-codebook index created portably. Outbound integration
points are backed by domain-parameterized respx mocks generated from the collaborators'
frozen producer shapes.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import httpx
import pytest
import respx
from acp_event_model import TrailsBuiltEvent, TypedEnvelope, serialize
from sqlalchemy import create_engine, event, text
from sqlalchemy.engine import Engine
from sqlalchemy.pool import StaticPool

from codebook_generator.bootstrap import build_components
from codebook_generator.config import IntegrationMode, Settings
from codebook_generator.store import CodebookStore, _metadata

# Distinct base URLs per integration point so the respx router can route by host.
TOPOLOGY_URL = "http://topology.test"
KNOWLEDGE_FO_URL = "http://knowledge-fo.test"
KNOWLEDGE_PT_URL = "http://knowledge-pt.test"
KNOWLEDGE_AV_URL = "http://knowledge-av.test"
TRAIL_BUILDER_URL = "http://trail-builder.test"


# --------------------------------------------------------------------------- #
# Store fixtures (SQLite + ATTACH 'codebook' schema)                          #
# --------------------------------------------------------------------------- #
@pytest.fixture
def engine() -> Iterator[Engine]:
    """In-memory SQLite engine with a ``codebook`` schema + service tables/indexes."""
    eng = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        future=True,
    )

    @event.listens_for(eng, "connect")
    def _attach_schema(dbapi_conn, _record):  # noqa: ANN001
        # Attach an in-memory DB under the name `codebook` so `codebook.<table>` resolves.
        dbapi_conn.execute("ATTACH DATABASE ':memory:' AS codebook")

    _metadata.create_all(eng)
    # Partial-unique index enforcing exactly one active codebook per (domain, snapshot_id).
    with eng.begin() as conn:
        conn.execute(
            text(
                "CREATE UNIQUE INDEX IF NOT EXISTS codebook.uq_codebooks_one_active "
                "ON codebooks (domain, snapshot_id) WHERE active = 1"
            )
        )
    yield eng
    eng.dispose()


@pytest.fixture
def store(engine: Engine) -> CodebookStore:
    """A Codebook Store backed by the in-memory engine."""
    return CodebookStore(engine)


# --------------------------------------------------------------------------- #
# Fake Kafka producer                                                         #
# --------------------------------------------------------------------------- #
class FakeProducer:
    """In-memory MessageProducer capturing produced messages per topic."""

    def __init__(self) -> None:
        self.messages: list[tuple[str, bytes, bytes | None]] = []
        self.fail_topics: set[str] = set()

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        if topic in self.fail_topics:
            raise RuntimeError(f"simulated produce failure on {topic}")
        self.messages.append((topic, value, key))

    def flush(self, timeout: float | None = None) -> int:
        return 0

    def topic_messages(self, topic: str) -> list[bytes]:
        return [v for (t, v, _k) in self.messages if t == topic]


@pytest.fixture
def fake_producer() -> FakeProducer:
    return FakeProducer()


# --------------------------------------------------------------------------- #
# Domain-parameterized collaborator data (frozen producer shapes)            #
# --------------------------------------------------------------------------- #
# Core IP fault-origin types with their self-emitted origin alarm tokens.
CORE_IP_FAULT_ORIGINS = [
    {"objectType": "FiberSpan", "originAlarmType": "FiberFault"},
    {"objectType": "LineCard", "originAlarmType": "CardFault"},
    {"objectType": "Port", "originAlarmType": "LOS"},
    {"objectType": "Interface", "originAlarmType": "InterfaceDown"},
    {"objectType": "Node", "originAlarmType": "NodeDown"},
]

# Core IP propagation templates — per-edge-type cascade rules (alarmType-vocabulary tokens).
CORE_IP_TEMPLATES = [
    # Fiber cut: FiberSpan -RIDES_ON-> IPLink
    {
        "edgeType": "RIDES_ON",
        "trigger": {"objectType": "FiberSpan", "alarmType": "FiberFault"},
        "effect": {"objectType": "IPLink", "alarmType": "LinkDown"},
    },
    # Interface fault: Interface -TERMINATES-> IPLink
    {
        "edgeType": "TERMINATES",
        "trigger": {"objectType": "Interface", "alarmType": "InterfaceDown"},
        "effect": {"objectType": "IPLink", "alarmType": "LinkDown"},
    },
    # Interface fault: Interface -ADJACENCY_OVER-> IGPAdjacency
    {
        "edgeType": "ADJACENCY_OVER",
        "trigger": {"objectType": "Interface", "alarmType": "InterfaceDown"},
        "effect": {"objectType": "IGPAdjacency", "alarmType": "AdjDown"},
    },
    # IPLink -TRAVERSES-> LSP
    {
        "edgeType": "TRAVERSES",
        "trigger": {"objectType": "IPLink", "alarmType": "LinkDown"},
        "effect": {"objectType": "LSP", "alarmType": "LSPDown"},
    },
    # LSP -SERVES-> VPNService
    {
        "edgeType": "SERVES",
        "trigger": {"objectType": "LSP", "alarmType": "LSPDown"},
        "effect": {"objectType": "VPNService", "alarmType": "ReachabilityLoss"},
    },
    # Port fault: Port -HOSTS-> Interface
    {
        "edgeType": "HOSTS",
        "trigger": {"objectType": "Port", "alarmType": "LOS"},
        "effect": {"objectType": "Interface", "alarmType": "InterfaceDown"},
    },
    # Line-card fault: LineCard -HOSTED_ON-> Port
    {
        "edgeType": "HOSTED_ON",
        "trigger": {"objectType": "LineCard", "alarmType": "CardFault"},
        "effect": {"objectType": "Port", "alarmType": "PortDown"},
    },
    # Port (when reached via HOSTED_ON, state PortDown) -HOSTS-> Interface
    {
        "edgeType": "HOSTS",
        "trigger": {"objectType": "Port", "alarmType": "PortDown"},
        "effect": {"objectType": "Interface", "alarmType": "InterfaceDown"},
    },
]

CORE_IP_VOCABULARY = [
    "FiberFault",
    "CardFault",
    "LOS",
    "PortDown",
    "InterfaceDown",
    "LinkDown",
    "AdjDown",
    "LSPDown",
    "ReachabilityLoss",
    "NodeDown",
]

# A distinct second domain (transport) — different object types + cascade rules.
TRANSPORT_FAULT_ORIGINS = [
    {"objectType": "OpticalAmp", "originAlarmType": "AmpFault"},
]
TRANSPORT_TEMPLATES = [
    {
        "edgeType": "FEEDS",
        "trigger": {"objectType": "OpticalAmp", "alarmType": "AmpFault"},
        "effect": {"objectType": "OpticalChannel", "alarmType": "ChannelLoss"},
    },
]
TRANSPORT_VOCABULARY = ["AmpFault", "ChannelLoss"]


# Per-domain topology graphs: object instances per fault-origin type, and per-instance closures.
def _node(mo_id: str, object_type: str, domain: str) -> dict:
    return {
        "managedObjectId": mo_id,
        "objectType": object_type,
        "domain": domain,
        "snapshotId": "current",
    }


# Core IP topology instances keyed by objectType.
CORE_IP_NODES: dict[str, list[dict]] = {
    "FiberSpan": [_node("FiberSpan:f1", "FiberSpan", "core-ip")],
    "LineCard": [_node("LineCard:c1", "LineCard", "core-ip")],
    "Port": [_node("Port:p1", "Port", "core-ip")],
    "Interface": [_node("Interface:i1", "Interface", "core-ip")],
    "Node": [],
}

# Per-instance bounded-traversal closures (reached nodes + typed edges).
CORE_IP_CLOSURES: dict[str, dict] = {
    # Fiber cut chain: f1 RIDES_ON l1, l1 TRAVERSES s1, s1 SERVES v1
    "FiberSpan:f1": {
        "reached": [
            _node("IPLink:l1", "IPLink", "core-ip"),
            _node("LSP:s1", "LSP", "core-ip"),
            _node("VPNService:v1", "VPNService", "core-ip"),
        ],
        "edges": [
            {"source": "FiberSpan:f1", "target": "IPLink:l1", "relation": "RIDES_ON"},
            {"source": "IPLink:l1", "target": "LSP:s1", "relation": "TRAVERSES"},
            {"source": "LSP:s1", "target": "VPNService:v1", "relation": "SERVES"},
        ],
    },
    # Interface cascade: i1 TERMINATES l1, i1 ADJACENCY_OVER a1, l1 TRAVERSES s1, s1 SERVES v1
    "Interface:i1": {
        "reached": [
            _node("IPLink:l1", "IPLink", "core-ip"),
            _node("IGPAdjacency:a1", "IGPAdjacency", "core-ip"),
            _node("LSP:s1", "LSP", "core-ip"),
            _node("VPNService:v1", "VPNService", "core-ip"),
        ],
        "edges": [
            {"source": "Interface:i1", "target": "IPLink:l1", "relation": "TERMINATES"},
            {"source": "Interface:i1", "target": "IGPAdjacency:a1", "relation": "ADJACENCY_OVER"},
            {"source": "IPLink:l1", "target": "LSP:s1", "relation": "TRAVERSES"},
            {"source": "LSP:s1", "target": "VPNService:v1", "relation": "SERVES"},
        ],
    },
    # Port fault: p1 HOSTS i1, then i1's interface cascade (TERMINATES l1)
    "Port:p1": {
        "reached": [
            _node("Interface:i1", "Interface", "core-ip"),
            _node("IPLink:l1", "IPLink", "core-ip"),
        ],
        "edges": [
            {"source": "Port:p1", "target": "Interface:i1", "relation": "HOSTS"},
            {"source": "Interface:i1", "target": "IPLink:l1", "relation": "TERMINATES"},
        ],
    },
    # Line-card fault: c1 HOSTED_ON p1 and p2 (two ports), each HOSTS an interface
    "LineCard:c1": {
        "reached": [
            _node("Port:p1", "Port", "core-ip"),
            _node("Port:p2", "Port", "core-ip"),
            _node("Interface:i1", "Interface", "core-ip"),
            _node("Interface:i2", "Interface", "core-ip"),
        ],
        "edges": [
            {"source": "LineCard:c1", "target": "Port:p1", "relation": "HOSTED_ON"},
            {"source": "LineCard:c1", "target": "Port:p2", "relation": "HOSTED_ON"},
            {"source": "Port:p1", "target": "Interface:i1", "relation": "HOSTS"},
            {"source": "Port:p2", "target": "Interface:i2", "relation": "HOSTS"},
        ],
    },
}

TRANSPORT_NODES: dict[str, list[dict]] = {
    "OpticalAmp": [_node("OpticalAmp:o1", "OpticalAmp", "transport")],
}
TRANSPORT_CLOSURES: dict[str, dict] = {
    "OpticalAmp:o1": {
        "reached": [_node("OpticalChannel:ch1", "OpticalChannel", "transport")],
        "edges": [
            {"source": "OpticalAmp:o1", "target": "OpticalChannel:ch1", "relation": "FEEDS"}
        ],
    },
}

_DOMAIN_DATA = {
    "core-ip": {
        "fault_origins": CORE_IP_FAULT_ORIGINS,
        "templates": CORE_IP_TEMPLATES,
        "vocabulary": CORE_IP_VOCABULARY,
        "nodes": CORE_IP_NODES,
        "closures": CORE_IP_CLOSURES,
    },
    "transport": {
        "fault_origins": TRANSPORT_FAULT_ORIGINS,
        "templates": TRANSPORT_TEMPLATES,
        "vocabulary": TRANSPORT_VOCABULARY,
        "nodes": TRANSPORT_NODES,
        "closures": TRANSPORT_CLOSURES,
    },
}


class MockCollaborators:
    """A respx router wiring all five integration points across both domains.

    Records every outbound request so tests can assert the params/paths carried.
    """

    def __init__(self) -> None:
        self.router = respx.Router(assert_all_mocked=True)
        self.requests: list[httpx.Request] = []
        self._wire()

    def _record(self, request: httpx.Request) -> None:
        self.requests.append(request)

    def calls_to(self, path_substring: str) -> list[httpx.Request]:
        return [r for r in self.requests if path_substring in r.url.path]

    def _wire(self) -> None:
        # Knowledge — fault-origin types
        @self.router.route(method="GET", host="knowledge-fo.test")
        def _fault_origins(request: httpx.Request) -> httpx.Response:
            self._record(request)
            domain = request.url.path.split("/")[2]
            return httpx.Response(200, json=_DOMAIN_DATA[domain]["fault_origins"])

        # Knowledge — propagation templates
        @self.router.route(method="GET", host="knowledge-pt.test")
        def _templates(request: httpx.Request) -> httpx.Response:
            self._record(request)
            domain = request.url.path.split("/")[2]
            return httpx.Response(200, json=_DOMAIN_DATA[domain]["templates"])

        # Knowledge — alarm-type vocabulary
        @self.router.route(method="GET", host="knowledge-av.test")
        def _vocab(request: httpx.Request) -> httpx.Response:
            self._record(request)
            domain = request.url.path.split("/")[2]
            return httpx.Response(
                200, json={"alarmTypes": _DOMAIN_DATA[domain]["vocabulary"]}
            )

        # Topology — nodes (list by type) + traversal
        @self.router.route(method="GET", host="topology.test", path="/topology/nodes")
        def _nodes(request: httpx.Request) -> httpx.Response:
            self._record(request)
            domain = request.url.params["domain"]
            object_type = request.url.params["objectType"]
            nodes = _DOMAIN_DATA[domain]["nodes"].get(object_type, [])
            return httpx.Response(
                200,
                json={
                    "domain": domain,
                    "objectType": object_type,
                    "snapshotId": request.url.params.get("snapshotId"),
                    "count": len(nodes),
                    "nodes": nodes,
                },
            )

        @self.router.route(method="GET", host="topology.test", path="/topology/traversal")
        def _traversal(request: httpx.Request) -> httpx.Response:
            self._record(request)
            domain = request.url.params["domain"]
            start = request.url.params["start"]
            closure = _DOMAIN_DATA[domain]["closures"].get(
                start, {"reached": [], "edges": []}
            )
            return httpx.Response(
                200,
                json={
                    "start": start,
                    "domain": domain,
                    "relations": request.url.params.get_list("relation"),
                    "maxDepth": int(request.url.params.get("maxDepth", "0")),
                    "crossDomain": False,
                    "reached": closure["reached"],
                    "edges": closure["edges"],
                },
            )

        # Trail Builder — by-object (domain required) returns at least one trail
        @self.router.route(method="GET", host="trail-builder.test", path="/trails/by-object")
        def _trails(request: httpx.Request) -> httpx.Response:
            self._record(request)
            mo_id = request.url.params["managedObjectId"]
            domain = request.url.params["domain"]
            return httpx.Response(
                200,
                json={
                    "managedObjectId": mo_id,
                    "domain": domain,
                    "trailIds": ["TRAIL-1"],
                },
            )

    def httpx_client(self) -> httpx.Client:
        return httpx.Client(transport=httpx.MockTransport(self.router.handler))


@pytest.fixture
def mocks() -> MockCollaborators:
    return MockCollaborators()


# --------------------------------------------------------------------------- #
# Settings / components wiring                                                #
# --------------------------------------------------------------------------- #
def make_settings(**overrides: object) -> Settings:
    """Build a fully-configured Settings with all integration URLs set (MOCK mode)."""
    base = dict(
        DATABASE_URL="sqlite://",
        TOPOLOGY_QUERY_URL=TOPOLOGY_URL,
        TOPOLOGY_QUERY_MODE=IntegrationMode.MOCK,
        KNOWLEDGE_FAULT_ORIGINS_URL=KNOWLEDGE_FO_URL,
        KNOWLEDGE_FAULT_ORIGINS_MODE=IntegrationMode.MOCK,
        KNOWLEDGE_PROPAGATION_TEMPLATES_URL=KNOWLEDGE_PT_URL,
        KNOWLEDGE_PROPAGATION_TEMPLATES_MODE=IntegrationMode.MOCK,
        KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL=KNOWLEDGE_AV_URL,
        KNOWLEDGE_ALARM_TYPE_VOCABULARY_MODE=IntegrationMode.MOCK,
        TRAIL_BUILDER_URL=TRAIL_BUILDER_URL,
        TRAIL_BUILDER_MODE=IntegrationMode.MOCK,
        INTEGRATION_MAX_RETRIES=2,
        INTEGRATION_BACKOFF_MS=1,
        DEFAULT_DOMAIN="core-ip",
    )
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def components(
    settings: Settings,
    store: CodebookStore,
    fake_producer: FakeProducer,
    mocks: MockCollaborators,
):
    """Wire the full pipeline + handler against mocked collaborators and the SQLite store."""
    return build_components(
        settings,
        message_producer=fake_producer,
        store=store,
        http_client=mocks.httpx_client(),
    )


# --------------------------------------------------------------------------- #
# Event-envelope helpers                                                      #
# --------------------------------------------------------------------------- #
def make_trails_built_envelope(
    *,
    snapshot_id: str = "snap-A",
    domain: str | None = "core-ip",
    trail_ids: list[str] | None = None,
    event_id: str | None = None,
    schema_version: int = 1,
) -> dict:
    """Build a wire dict for a TrailsBuiltEvent (round-trip safe via serialize)."""
    trail_ids = trail_ids if trail_ids is not None else ["TRAIL-1"]
    payload = TrailsBuiltEvent(
        snapshotId=snapshot_id,
        domain=domain,
        trailIds=trail_ids,
        trailCount=len(trail_ids),
    )
    envelope = TypedEnvelope[TrailsBuiltEvent](
        eventId=event_id or str(uuid.uuid4()),
        type="TrailsBuiltEvent",
        schemaVersion=schema_version,
        occurredAt=datetime.now(UTC),
        source="trail-builder",
        traceId="trace-1",
        payload=payload,
    )
    wire = envelope.to_dict()
    wire["schemaVersion"] = schema_version
    return wire


def trails_built_bytes(**kwargs: object) -> bytes:
    """Serialize a TrailsBuiltEvent to wire bytes for the consumer handler."""
    import json

    return json.dumps(make_trails_built_envelope(**kwargs)).encode("utf-8")  # type: ignore[arg-type]
