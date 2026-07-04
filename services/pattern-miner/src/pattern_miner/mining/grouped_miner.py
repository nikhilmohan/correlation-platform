"""Stage 3 — bounded PrefixSpan **per anchored group** (re-scoped from the old global mining).

``GroupedMiner`` iterates the Stage-2 anchored groups and, for **each** group, runs the existing
:class:`~pattern_miner.mining.miner.PrefixSpanMiner` over **only that group's** session sequences
(ordered lists of ``alarmType`` tokens). This bounded scope is what removes the global-mining OOM
(the old design ran one PrefixSpan over the full dense corpus, kernel-killing the JVM and emitting
~2,592 frequent sub-fragments) and yields, per group, that fault-origin's canonical signature.

Per group it selects a single **representative** learned pattern — the maximal frequent ordered
sequence meeting ``minSupport`` (longest, then highest support, then lexicographic). That single
representative becomes the group's one ``PatternMinedEvent`` (AC-9, AC-10, AC-20). A group whose
PrefixSpan yields no frequent sequence at the current ``minSupport`` produces no pattern (a valid
empty outcome). ``minSupport`` / ``maxPatternLength`` / ``maxSequenceCount`` are Knowledge-sourced —
no threshold literal here.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..anchoring import AnchoredGroup
from ..config import MiningParams
from ..logging_setup import get_logger
from ..windowing import Session
from .miner import MinedSequence, PrefixSpanMiner

log = get_logger(__name__)


@dataclass(frozen=True)
class GroupPattern:
    """The representative learned pattern for one anchored group (+ its matching sessions)."""

    scenario_id: str | None
    mined: MinedSequence
    matching_sessions: list[Session]


class GroupedMiner:
    """Runs bounded PrefixSpan within each anchored group and selects its representative pattern."""

    def __init__(self, miner: PrefixSpanMiner, *, metrics=None) -> None:
        self._miner = miner
        self._metrics = metrics

    def mine_group(self, group: AnchoredGroup, params: MiningParams) -> GroupPattern | None:
        """Mine one group's sessions (bounded); return its representative pattern or ``None``."""
        sequences = [s.sequence for s in group.sessions]
        mined = self._miner.mine(
            sequences,
            min_support=params.min_support,
            max_pattern_length=params.max_pattern_length,
            max_sequence_count=params.max_sequence_count,
        )
        if not mined:
            log.info(
                "group_mining_empty",
                scenario_id=group.scenario_id,
                sessions=len(group.sessions),
            )
            return None

        representative = _select_representative(mined)
        matching = [
            s for s in group.sessions if _contains_subsequence(s.sequence, representative.sequence)
        ]
        log.info(
            "group_mining_completed",
            scenario_id=group.scenario_id,
            sessions=len(group.sessions),
            sequence=list(representative.sequence),
            support=round(representative.support, 6),
        )
        return GroupPattern(
            scenario_id=group.scenario_id,
            mined=representative,
            matching_sessions=matching,
        )

    def mine(self, groups: list[AnchoredGroup], params: MiningParams) -> list[GroupPattern]:
        """Mine every anchored group; return one :class:`GroupPattern` per non-empty group."""
        patterns: list[GroupPattern] = []
        for group in groups:
            pattern = self.mine_group(group, params)
            if pattern is not None:
                patterns.append(pattern)
        return patterns


def _select_representative(mined: list[MinedSequence]) -> MinedSequence:
    """Pick a group's representative signature: longest, then highest support, then lexicographic.

    Longest-first favours the fault-origin's full canonical chain over its sub-fragments (so the
    emitted pattern is the cascade, not a length-1 junk token); support + lexicographic break ties
    deterministically.
    """
    return max(mined, key=lambda m: (len(m.sequence), m.support, _neg_lex(m.sequence)))


def _neg_lex(sequence: tuple[str, ...]) -> tuple[int, ...]:
    """A tie-break key that makes the lexicographically-smallest sequence sort *highest*."""
    return tuple(-ord(c) for c in "".join(sequence))


def _contains_subsequence(session_seq: list[str], pattern: tuple[str, ...]) -> bool:
    """True iff ``pattern`` is an ordered subsequence of ``session_seq`` (gaps allowed)."""
    it = iter(session_seq)
    return all(any(token == p for token in it) for p in pattern)
