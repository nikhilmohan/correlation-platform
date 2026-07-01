"""Assemble ``PatternMinedEvent`` envelopes from sessions + mined sequences + timing + provenance.

The end-to-end mining assembly (spec tasks 3-7): pool per-trail alarms -> adaptive session windows
-> PrefixSpan over the ``alarmType``-token session sequences -> support/confidence/lift -> ms timing
-> one ``PatternMinedEvent`` per discovered sequence on ``patterns.mined``, carrying provenance
(``sourceWindowId``, ``snapshotId``, ``codebookVersion``, ``domain``) and NO RCA/lifecycle/patternId
fields (structurally impossible — the frozen schema forbids extras).

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

from .config import MiningParams
from .logging_setup import get_logger
from .mining import MinedSequence, PrefixSpanMiner
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


class PatternAssembler:
    """Builds ``PatternMinedEvent`` envelopes for the sequences mined from a trail's sessions."""

    def __init__(
        self,
        windower: SessionWindower,
        miner: PrefixSpanMiner,
        timing_computer: TimingComputer,
        *,
        metrics=None,
    ) -> None:
        self._windower = windower
        self._miner = miner
        self._timing = timing_computer
        self._metrics = metrics

    def mine_batch(self, batch: TrailBatch, params: MiningParams) -> list[TypedEnvelope]:
        """Window, mine, and assemble ``PatternMinedEvent`` envelopes for one trail batch."""
        sessions = self._windower.sessions_for_trail(
            batch.trail_id,
            batch.alarms,
            snapshot_id=batch.snapshot_id,
            domain=batch.domain,
        )
        if not sessions:
            return []

        sequences = [s.sequence for s in sessions]
        mined = self._miner.mine(
            sequences,
            min_support=params.min_support,
            max_pattern_length=params.max_pattern_length,
            max_sequence_count=params.max_sequence_count,
        )
        if not mined:
            log.info("mining_empty_result", trail_id=batch.trail_id, sessions=len(sessions))
            return []

        envelopes: list[TypedEnvelope] = []
        for m in mined:
            matching = [s for s in sessions if _contains_subsequence(s.sequence, m.sequence)]
            envelope = self._build_event(batch, m, matching, params)
            envelopes.append(envelope)
            if self._metrics is not None:
                self._metrics.patterns_emitted.inc()
            log.info(
                "pattern_mined",
                trail_id=batch.trail_id,
                sequence=list(m.sequence),
                support=round(m.support, 6),
                confidence=round(m.confidence, 6),
                lift=round(m.lift, 6),
                timing=envelope.payload.timing,
            )
        return envelopes

    def _build_event(
        self,
        batch: TrailBatch,
        mined: MinedSequence,
        matching_sessions: list[Session],
        params: MiningParams,
    ) -> TypedEnvelope:
        timing = self._timing.compute([s.alarms for s in matching_sessions])
        # sourceWindowId: the composite reference of the matching sessions. When a sequence spans
        # multiple sessions we use the first matching session's id (stable per input+boundary); the
        # window-set is fully determined by trail+snapshot+params.
        source_window_id = matching_sessions[0].source_window_id

        provenance = Provenance(
            sourceWindowId=source_window_id,
            snapshotId=batch.snapshot_id,
            domain=batch.domain,
            codebookVersion=params.codebook_version,
        )
        payload = PatternMinedEvent(
            sequence=list(mined.sequence),
            support=mined.support,
            confidence=mined.confidence,
            lift=mined.lift,
            trailId=batch.trail_id,
            timing=timing.to_dict(),
            provenance=provenance,
        )
        return TypedEnvelope(
            eventId=str(uuid.uuid4()),
            type="PatternMinedEvent",
            schemaVersion=1,
            occurredAt=datetime.now(UTC),
            source=SOURCE,
            traceId=batch.trace_id or str(uuid.uuid4()),
            payload=payload,
        )


def _contains_subsequence(session_seq: list[str], pattern: tuple[str, ...]) -> bool:
    """True iff ``pattern`` is an ordered subsequence of ``session_seq`` (gaps allowed)."""
    it = iter(session_seq)
    return all(any(token == p for token in it) for p in pattern)
