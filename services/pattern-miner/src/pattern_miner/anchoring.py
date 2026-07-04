"""Stage 2 — domain-knowledge anchoring (the accuracy crux).

Assigns each Stage-1 candidate cascade to exactly one fault-origin identity (a Codebook
``scenarioId``) or to the "unexplained" group, so the emitted pattern set is small and accurate:
**zero over-split** (variants of one fault-origin collapse to one anchor) and **zero over-merge**
(one anchored group maps to one fault-origin).

The scorer (design "Stage 2b") combines two normalized measures over the cascade's ordered
``alarmType`` sequence ``C`` and a scenario's ordered ``predictedSymptoms[].alarmType`` chain ``S``:

    lcs_ratio  = LCS(C_ordered, S) / len(S)          # ordered chain coverage -> no OVER-SPLIT
    jaccard    = |set(C) & set(S)| / |set(C) | set(S)|  # set overlap (union) -> no OVER-MERGE
    confidence = w_order * lcs_ratio + w_jaccard * jaccard   # weights from Knowledge (sum to 1)

``w_order`` / ``w_jaccard`` and the ``match_confidence_threshold`` are **Knowledge-sourced**
(``AnchoringParams``) — there is no hard-coded weight or threshold here (spec AC-7/AC-17). A cascade
is anchored to the ``argmax`` scenario iff the max confidence reaches the threshold (single-anchor
assignment — never placed in two groups); otherwise it is "unexplained". Ties break
deterministically (longer scenario chain, then lexicographically-smallest ``scenarioId``). The whole
component is domain-agnostic: the alarm vocabulary is whatever ``alarms[].alarmType`` values arrive
and the scenario chains are whatever the Codebook returns.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .codebook import Scenario
from .config import AnchoringParams
from .logging_setup import get_logger
from .windowing import Session

log = get_logger(__name__)

# The one scorer the reusable template ships (selected by Knowledge ``anchoring.scoringMethod``).
SCORING_ORDERED_SUBSEQUENCE_JACCARD = "ordered_subsequence_jaccard"


def _dedup_consecutive(tokens: list[str]) -> list[str]:
    """Collapse consecutive repeats (storms repeat a token) while preserving order."""
    out: list[str] = []
    for t in tokens:
        if not out or out[-1] != t:
            out.append(t)
    return out


def _lcs_length(a: list[str], b: tuple[str, ...]) -> int:
    """Length of the longest common subsequence of ``a`` and ``b`` (order preserved, gaps ok)."""
    if not a or not b:
        return 0
    prev = [0] * (len(b) + 1)
    for x in a:
        curr = [0]
        for j, y in enumerate(b, start=1):
            if x == y:
                curr.append(prev[j - 1] + 1)
            else:
                curr.append(max(prev[j], curr[j - 1]))
        prev = curr
    return prev[-1]


def score_cascade(
    cascade_tokens: list[str], symptom_chain: tuple[str, ...], params: AnchoringParams
) -> float:
    """Weighted LCS-ratio + Jaccard confidence in ``[0, 1]`` for one cascade vs one scenario chain.

    Empty scenario chain -> ``0.0`` (nothing to match). Weights are Knowledge-sourced.
    """
    if not symptom_chain:
        return 0.0
    ordered = _dedup_consecutive(cascade_tokens)
    lcs_ratio = _lcs_length(ordered, symptom_chain) / len(symptom_chain)

    c_set = set(ordered)
    s_set = set(symptom_chain)
    union = c_set | s_set
    jaccard = (len(c_set & s_set) / len(union)) if union else 0.0

    return params.w_order * lcs_ratio + params.w_jaccard * jaccard


@dataclass(frozen=True)
class MatchResult:
    """The anchoring outcome for one cascade: its best scenario (or ``None``) and confidence."""

    session: Session
    scenario_id: str | None
    confidence: float


@dataclass(frozen=True)
class AnchoredGroup:
    """A group of cascades sharing one anchor (``scenario_id`` ``None`` = the unexplained group)."""

    scenario_id: str | None
    scenario: Scenario | None
    sessions: list[Session] = field(default_factory=list)

    @property
    def is_unexplained(self) -> bool:
        return self.scenario_id is None


class CascadeMatcher:
    """Scores each cascade against every scenario chain and assigns its single best anchor."""

    def __init__(self, scenarios: list[Scenario], params: AnchoringParams) -> None:
        self._scenarios = scenarios
        self._params = params

    def _tie_break_key(self, scenario: Scenario) -> tuple[int, str]:
        """Deterministic tie-break: longer chain wins, then lexicographically-smallest id.

        Returns a sort key that is *minimized* by ``min(...)`` — ``-len(chain)`` so a longer chain
        sorts first, then the raw id. Governed by Knowledge ``anchoring.tieBreak`` (structural).
        """
        return (-len(scenario.symptom_chain), scenario.scenario_id)

    def match(self, session: Session) -> MatchResult:
        """Return the best :class:`MatchResult` for one cascade (argmax, threshold-gated)."""
        tokens = session.sequence
        best: Scenario | None = None
        best_conf = -1.0
        for scenario in self._scenarios:
            conf = score_cascade(tokens, scenario.symptom_chain, self._params)
            if conf > best_conf or (
                conf == best_conf
                and best is not None
                and self._tie_break_key(scenario) < self._tie_break_key(best)
            ):
                best = scenario
                best_conf = conf

        if best is None or best_conf < self._params.match_confidence_threshold:
            log.info(
                "cascade_anchored",
                source_window_id=session.source_window_id,
                scenario_id=None,
                confidence=round(max(best_conf, 0.0), 6),
                outcome="unexplained",
            )
            return MatchResult(session=session, scenario_id=None, confidence=max(best_conf, 0.0))

        log.info(
            "cascade_anchored",
            source_window_id=session.source_window_id,
            scenario_id=best.scenario_id,
            confidence=round(best_conf, 6),
            outcome="anchored",
        )
        return MatchResult(session=session, scenario_id=best.scenario_id, confidence=best_conf)


class AnchorGrouper:
    """Groups cascades by their assigned anchor (one group per scenarioId + one unexplained)."""

    def __init__(self, scenarios: list[Scenario], params: AnchoringParams) -> None:
        self._matcher = CascadeMatcher(scenarios, params)
        self._by_id = {s.scenario_id: s for s in scenarios}
        self._params = params

    def group(self, sessions: list[Session]) -> list[AnchoredGroup]:
        """Assign every cascade its single anchor, then group by that anchor.

        Yields at most ``N + 1`` groups for ``N`` distinct anchored fault-origins (AC-10); by
        construction no cascade appears in two groups (AC-4/AC-5/AC-20). Group order is stable:
        anchored groups by ``scenarioId`` (lexicographic), the unexplained group last.
        """
        buckets: dict[str | None, list[Session]] = {}
        for session in sessions:
            result = self._matcher.match(session)
            buckets.setdefault(result.scenario_id, []).append(result.session)

        groups: list[AnchoredGroup] = []
        for scenario_id in sorted(k for k in buckets if k is not None):
            groups.append(
                AnchoredGroup(
                    scenario_id=scenario_id,
                    scenario=self._by_id.get(scenario_id),
                    sessions=buckets[scenario_id],
                )
            )
        if None in buckets:
            groups.append(AnchoredGroup(scenario_id=None, scenario=None, sessions=buckets[None]))
        return groups
