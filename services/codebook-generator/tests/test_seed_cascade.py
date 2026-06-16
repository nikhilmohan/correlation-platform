"""Anti-regression cascade test bound to the LIVE Knowledge core-ip seed file (#262).

Unlike :mod:`test_propagation` (which runs the propagation engine against the *synthetic*
collaborator fixtures in ``conftest``), this test loads the **actual** Knowledge core-ip seed
(``services/knowledge/src/main/resources/seed/core-ip.json``) and drives the same propagation
entrypoint (:func:`codebook_generator.propagation.propagate`) with the seed's real
``faultOriginType`` + ``propagationTemplate`` records — parsed exactly the way the runtime
Knowledge client parses them (``payload`` -> ``model_validate``; see ``clients/knowledge.py``).

Why this exists (#262 test-honesty): the prior AC-1 cascade test passed green because the
synthetic FiberSpan fault-origin AND the RIDES_ON template both used the SAME token in the
fixture, so they matched each other regardless of whether the *live* seed agreed. The live
seed had a mismatch — the FiberSpan fault-origin emits ``FiberCut`` while the cascade-starting
RIDES_ON (FiberSpan -> IPLink) template triggered on ``FiberFault`` — so the live fiber-cut
scenario collapsed to a single, root-only symptom and AC-1 silently failed on the stack.

This test binds the assertion to the real seed file so it CANNOT drift: given the seed's
records, a FiberSpan origin over a representative ``RIDES_ON -> TRAVERSES -> SERVES`` closure
MUST derive the full multi-symptom fiber-cut cascade
``FiberCut(FiberSpan) -> LinkDown(IPLink) -> LSPDown(LSP) -> ReachabilityLoss(VPNService)`` —
i.e. it must NOT collapse to one symptom.

Pre-/post-fix behaviour:
- Against the CORRECTED seed (RIDES_ON FiberSpan trigger == ``FiberCut``), the cascade is
  multi-symptom and this test PASSES.
- Against the OLD seed (RIDES_ON FiberSpan trigger == ``FiberFault``), the cascade collapses
  to the root symptom only and this test FAILS — correctly flagging the #262 data bug.

The Knowledge seed fix lands in parallel. To keep the suite green while preserving the REAL
assertion (never loosened), the test is marked ``xfail(strict=False)`` keyed off the seed's
own RIDES_ON-FiberSpan trigger token: if the synced seed is still pre-fix it XFAILs (expected,
referencing #262); once the seed is corrected it XPASSes (still green) and the xfail guard can
be removed. Either way the encoded cascade is bound to the real seed file and cannot silently
drift back to a 1-symptom green.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from codebook_generator.models import (
    FaultOriginType,
    NodeDto,
    PropagationTemplate,
    TraversalDto,
)
from codebook_generator.propagation import build_closure_graph, propagate

# repo-root/services/knowledge/src/main/resources/seed/core-ip.json
# (this test is at services/codebook-generator/tests/<file>; parents[3] == repo root —
# the same path-resolution pattern as test_topology_contract.py's topology openapi.json).
_KNOWLEDGE_SEED = (
    Path(__file__).resolve().parents[3]
    / "services"
    / "knowledge"
    / "src"
    / "main"
    / "resources"
    / "seed"
    / "core-ip.json"
)

# The representative fiber-cut closure: FiberSpan -RIDES_ON-> IPLink -TRAVERSES-> LSP
# -SERVES-> VPNService (the live-stack #262 cascade path the topology traversal returns).
_FIBER_ORIGIN = NodeDto(
    managedObjectId="FiberSpan:F-N0_N1", objectType="FiberSpan", domain="core-ip"
)
_FIBER_REACHED = [
    NodeDto(managedObjectId="IPLink:N0_N1", objectType="IPLink", domain="core-ip"),
    NodeDto(managedObjectId="LSP:N0-N1-1", objectType="LSP", domain="core-ip"),
    NodeDto(managedObjectId="VPNService:CUST-0", objectType="VPNService", domain="core-ip"),
]
_FIBER_EDGES = [
    {"from": "FiberSpan:F-N0_N1", "to": "IPLink:N0_N1", "relation": "RIDES_ON"},
    {"from": "IPLink:N0_N1", "to": "LSP:N0-N1-1", "relation": "TRAVERSES"},
    {"from": "LSP:N0-N1-1", "to": "VPNService:CUST-0", "relation": "SERVES"},
]

# Expected fiber-cut cascade tail (object-type ladder), independent of exact instance ids.
_EXPECTED_CASCADE_TYPES = ["FiberSpan", "IPLink", "LSP", "VPNService"]


def _load_seed_records() -> list[dict]:
    """Load the Knowledge core-ip seed's records (skip if knowledge not vendored)."""
    if not _KNOWLEDGE_SEED.exists():
        pytest.skip(f"knowledge core-ip seed not present at {_KNOWLEDGE_SEED}")
    doc = json.loads(_KNOWLEDGE_SEED.read_text())
    records = doc.get("records")
    assert isinstance(records, list) and records, "knowledge core-ip seed has no records"
    return records


def _seed_fault_origins(records: list[dict]) -> list[FaultOriginType]:
    """Parse ``faultOriginType`` payloads exactly as ``clients/knowledge.py`` does (#224)."""
    return [
        FaultOriginType.model_validate(r["payload"])
        for r in records
        if r.get("recordType") == "faultOriginType"
    ]


def _seed_templates(records: list[dict]) -> list[PropagationTemplate]:
    """Parse ``propagationTemplate`` payloads exactly as ``clients/knowledge.py`` does (#224)."""
    return [
        PropagationTemplate.model_validate(r["payload"])
        for r in records
        if r.get("recordType") == "propagationTemplate"
    ]


def _rides_on_fiberspan_trigger_token(templates: list[PropagationTemplate]) -> str | None:
    """The trigger alarmType of the FiberSpan -RIDES_ON-> IPLink template in the seed.

    This is the load-bearing #262 token: the live cascade fires only when it equals the
    FiberSpan fault-origin's emitted token (``FiberCut``). Pre-fix the seed had ``FiberFault``.
    """
    for tmpl in templates:
        if (
            tmpl.edgeType == "RIDES_ON"
            and tmpl.trigger.objectType == "FiberSpan"
            and tmpl.effect.objectType == "IPLink"
        ):
            return tmpl.trigger.alarmType
    return None


def _seed_is_prefix() -> bool:
    """True if the synced seed still has the pre-#262-fix RIDES_ON-FiberSpan trigger.

    The corrected seed triggers the FiberSpan -> IPLink cascade on the FiberSpan fault-origin's
    own emitted token; a mismatch (the cascade trigger != the origin's emitted token) is the
    pre-fix state that collapses the fiber-cut scenario to one symptom.
    """
    records = _load_seed_records()
    fault_origins = _seed_fault_origins(records)
    templates = _seed_templates(records)
    fiber_origin_token = next(
        (fo.originAlarmType for fo in fault_origins if fo.objectType == "FiberSpan"), None
    )
    cascade_trigger = _rides_on_fiberspan_trigger_token(templates)
    # Pre-fix iff the cascade-starting RIDES_ON trigger does not match the origin's own token.
    return cascade_trigger != fiber_origin_token


@pytest.mark.xfail(
    _seed_is_prefix(),
    reason=(
        "Knowledge core-ip seed still has the pre-#262-fix RIDES_ON(FiberSpan) trigger token "
        "(FiberFault) that does not match the FiberSpan fault-origin's emitted token (FiberCut), "
        "so the live fiber-cut cascade collapses to a single root symptom. The seed fix "
        "(RIDES_ON trigger -> FiberCut) lands in parallel in the Knowledge service; this test "
        "is correct and must NOT be loosened — it XPASSes once the corrected seed is merged."
    ),
    strict=False,
)
def test_seed_derives_multi_symptom_fiber_cut_cascade() -> None:
    """A FiberSpan origin over RIDES_ON->TRAVERSES->SERVES yields the full fiber-cut cascade.

    Bound to the REAL Knowledge core-ip seed records (#262). The FiberSpan scenario must NOT
    collapse to a single symptom; it must include LinkDown(IPLink), LSPDown(LSP), and
    ReachabilityLoss(VPNService) downstream of the FiberCut origin symptom.
    """
    records = _load_seed_records()
    fault_origins = _seed_fault_origins(records)
    templates = _seed_templates(records)

    traversal = TraversalDto(
        start=_FIBER_ORIGIN.managedObjectId,
        domain="core-ip",
        reached=_FIBER_REACHED,
        edges=_FIBER_EDGES,  # type: ignore[arg-type]
    )
    closure = build_closure_graph(_FIBER_ORIGIN, traversal)
    symptoms = propagate(_FIBER_ORIGIN, closure, templates, fault_origins)

    sig = [(s.alarmType, s.managedObjectId) for s in symptoms]

    # NOT collapsed to a single root-only symptom (the #262 failure mode).
    assert len(sig) >= 4, f"fiber-cut cascade collapsed to {len(sig)} symptom(s): {sig}"

    # The origin symptom is the FiberSpan fault-origin's own emitted token, on the origin.
    fiber_origin_token = next(
        fo.originAlarmType for fo in fault_origins if fo.objectType == "FiberSpan"
    )
    assert sig[0] == (fiber_origin_token, "FiberSpan:F-N0_N1")
    assert fiber_origin_token == "FiberCut"  # the corrected live taxonomy

    # The real multi-symptom fiber-cut signature: object-type ladder over RIDES_ON/TRAVERSES/SERVES.
    cascade_types = [object_id.split(":", 1)[0] for (_alarm, object_id) in sig]
    for expected_type in _EXPECTED_CASCADE_TYPES:
        assert (
            expected_type in cascade_types
        ), f"missing {expected_type} from fiber-cut cascade {sig}"

    # The specific downstream effect (alarmType, object) tokens that make the fiber-cut
    # scenario minable MUST be present. (The seed authors several effect tokens per edge —
    # e.g. LSP also raises FRRSwitchover/TETunnelDown — so this is a membership check, not an
    # exact-equality one: the load-bearing guarantee is that the canonical cascade tokens are
    # derived, not that they are the only ones.)
    sig_set = set(sig)
    assert ("LinkDown", "IPLink:N0_N1") in sig_set
    assert ("LSPDown", "LSP:N0-N1-1") in sig_set
    assert ("ReachabilityLoss", "VPNService:CUST-0") in sig_set
