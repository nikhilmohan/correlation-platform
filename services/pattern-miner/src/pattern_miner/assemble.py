"""Assemble ``PatternMinedEvent`` envelopes from the 3-stage discovery pipeline.

The end-to-end mining assembly (spec tasks 3-7), redesigned to the 3-stage approach:

1. **Stage 1 — time+space correlation** (``windowing.SessionWindower``): pool per-trail alarms,
   adaptive idle-gap session windowing -> per-trail **candidate cascades** (``Session``s).
2. **Stage 2 — domain-knowledge anchoring** (``anchoring.AnchorGrouper``): assign each cascade to
   the best-matching Codebook fault-origin ``scenarioId`` (or "unexplained"); group by anchor.
3. **Stage 3 — bounded PrefixSpan per group** (``mining.GroupedMiner``): learn each group's
   canonical ordered ``alarmType`` signature, support, confidence, lift -> **one**
   ``PatternMinedEvent`` per anchored group (+ one unexplained if non-empty) on ``patterns.mined``.

Provenance carries ``sourceWindowId``, ``snapshotId``, ``codebookVersion``, ``domain``, and
``anchorScenarioId`` (the group's matched ``scenarioId``, or ``None`` for the unexplained group) —
and NO RCA/lifecycle/patternId fields (structurally impossible — the frozen schema forbids extras).

The mined ``sequence`` items are the ``alarms[].alarmType`` tokens (the canonical join token) —
never ``eventType`` (X.733 category) or ``probableCause``.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

from acp_event_model import (
    Alarm,
    PatternMinedEvent,
    Provenance,
    TransactionEvent,
    TypedEnvelope,
)

from .anchoring import AnchorGrouper
from .codebook import Scenario
from .config import MiningParams
from .logging_setup import get_logger
from .mining import GroupedMiner, GroupPattern
from .timing import TimingComputer
from .windowing import Session, SessionWindower

log = get_logger(__name__)

SOURCE = "pattern-miner"


@dataclass(frozen=True)
class TrailBatch:
    """A trail's pooled transactions for one mining run (all share a trailId)."""

    trail_id: str
    snapshot_id: str
    domain: str | None
    alarms: list[Alarm]
    trace_id: str | None


def group_transactions(
    transactions: list[tuple[TransactionEvent, str | None]],
) -> list[TrailBatch]:
    """Pool typed alarms per ``trailId`` across a run's transactions.

    ``transactions`` is a list of ``(TransactionEvent, traceId)``. Alarms from every transaction
    on the same trail are pooled; the trail's ``snapshotId``/``domain`` come from its transactions
    (the last-seen snapshot for that trail wins, matching a single-snapshot learning window).
    """
    batches: dict[str, TrailBatch] = {}
    for txn, trace_id in transactions:
        existing = batches.get(txn.trailId)
        pooled = list(existing.alarms) if existing else []
        pooled.extend(txn.alarms)
        batches[txn.trailId] = TrailBatch(
            trail_id=txn.trailId,
            snapshot_id=txn.snapshotId,
            domain=txn.domain,
            alarms=pooled,
            trace_id=(existing.trace_id if existing else trace_id),
        )
    return list(batches.values())


class ThreeStagePipeline:
    """Runs Stage 1 -> Stage 2 -> Stage 3 over a run's trail batches and assembles the events.

    One instance is built per run with the run's Knowledge-sourced ``params`` and the once-per-run
    Codebook ``scenarios``. It emits **one** ``PatternMinedEvent`` per anchored group (plus one for
    the unexplained group if non-empty) — the bounded, accurate output the respec requires.
    """

    def __init__(
        self,
        windower: SessionWindower,
        grouped_miner: GroupedMiner,
        timing_computer: TimingComputer,
        *,
        metrics=None,
    ) -> None:
        self._windower = windower
        self._grouped_miner = grouped_miner
        self._timing = timing_computer
        self._metrics = metrics

    def run(
        self,
        batches: list[TrailBatch],
        scenarios: list[Scenario],
        params: MiningParams,
    ) -> list[TypedEnvelope]:
        """Execute the 3 stages over the whole run and return the assembled envelopes."""
        # Stage 1: window every trail into candidate cascades (sessions).
        sessions: list[Session] = []
        trace_by_window: dict[str, str | None] = {}
        for batch in batches:
            trail_sessions = self._windower.sessions_for_trail(
                batch.trail_id,
                batch.alarms,
                snapshot_id=batch.snapshot_id,
                domain=batch.domain,
            )
            for s in trail_sessions:
                trace_by_window[s.source_window_id] = batch.trace_id
            sessions.extend(trail_sessions)

        if self._metrics is not None:
            self._metrics.last_run_session_count.set(len(sessions))
        if not sessions:
            return []

        # Stage 2: anchor each cascade to a scenario (or unexplained) and group.
        grouper = AnchorGrouper(scenarios, params.anchoring)
        groups = grouper.group(sessions)
        self._record_anchor_metrics(groups)

        # Stage 3: bounded PrefixSpan within each group; one representative pattern per group.
        group_patterns = self._grouped_miner.mine(groups, params)

        envelopes: list[TypedEnvelope] = []
        for gp in group_patterns:
            envelope = self._build_event(gp, params, trace_by_window)
            envelopes.append(envelope)
            if self._metrics is not None:
                self._metrics.patterns_emitted.inc()
            log.info(
                "pattern_mined",
                anchor_scenario_id=gp.scenario_id,
                sequence=list(gp.mined.sequence),
                support=round(gp.mined.support, 6),
                confidence=round(gp.mined.confidence, 6),
                lift=round(gp.mined.lift, 6),
                timing=envelope.payload.timing,
            )
        return envelopes

    def _record_anchor_metrics(self, groups) -> None:
        if self._metrics is None:
            return
        anchored_groups = 0
        anchored_cascades = 0
        unexplained_cascades = 0
        for g in groups:
            if g.is_unexplained:
                unexplained_cascades += len(g.sessions)
            else:
                anchored_groups += 1
                anchored_cascades += len(g.sessions)
        self._metrics.anchored_group_count.set(anchored_groups)
        self._metrics.cascades_anchored.inc(anchored_cascades)
        self._metrics.cascades_unexplained.inc(unexplained_cascades)

    def _build_event(
        self,
        gp: GroupPattern,
        params: MiningParams,
        trace_by_window: dict[str, str | None],
    ) -> TypedEnvelope:
        # timing aggregates over ALL matching sessions in the group (full observed tempo).
        timing = self._timing.compute([s.alarms for s in gp.matching_sessions])
        first = gp.matching_sessions[0]
        source_window_id = first.source_window_id
        trace_id = trace_by_window.get(source_window_id)

        provenance = Provenance(
            sourceWindowId=source_window_id,
            snapshotId=first.snapshot_id,
            domain=first.domain,
            codebookVersion=params.codebook_version,
            anchorScenarioId=gp.scenario_id,
        )
        payload = PatternMinedEvent(
            sequence=list(gp.mined.sequence),
            support=gp.mined.support,
            confidence=gp.mined.confidence,
            lift=gp.mined.lift,
            trailId=first.trail_id,
            timing=timing.to_dict(),
            provenance=provenance,
        )
        return TypedEnvelope(
            eventId=str(uuid.uuid4()),
            type="PatternMinedEvent",
            schemaVersion=1,
            occurredAt=datetime.now(UTC),
            source=SOURCE,
            traceId=trace_id or str(uuid.uuid4()),
            payload=payload,
        )
