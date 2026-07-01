"""Pattern emission: produce one ``PatternMinedEvent`` envelope per discovered sequence.

The emitter is the thin boundary between the assembled ``TypedEnvelope``s and the Kafka producer;
it serializes each envelope to canonical wire JSON (via the event-model codec) and produces it to
``patterns.mined``. Kept transport-agnostic (any object exposing ``publish(topic, envelope)``) so
it is unit-testable against a fake producer.
"""

from __future__ import annotations

from typing import Any

from acp_event_model import TypedEnvelope

from .logging_setup import get_logger

log = get_logger(__name__)


class PatternEmitter:
    """Publishes ``PatternMinedEvent`` envelopes to ``patterns.mined`` (one per sequence)."""

    def __init__(self, producer: Any, patterns_topic: str, *, metrics=None) -> None:
        self._producer = producer
        self._topic = patterns_topic
        self._metrics = metrics

    def emit(self, envelopes: list[TypedEnvelope]) -> int:
        """Produce each envelope; return the count emitted.

        Fail-fast on produce error (design.md "Failure handling & retry": a produce failure
        means the run is *not* committed — offsets are not committed for the failed batch — so
        the job surfaces the error and exits non-zero, letting the orchestrator restart and
        re-consume. At-least-once redelivery + envelope ``eventId`` dedupe make that replay
        safe, so mid-batch partial emission does not create duplicate patterns). We therefore
        count the failure (``produce_failures``) and re-raise rather than swallowing it — a
        silently-dropped ``PatternMinedEvent`` would be lost with no replay.
        """
        emitted = 0
        for envelope in envelopes:
            try:
                self._producer.publish(self._topic, envelope)
                emitted += 1
            except Exception as exc:  # noqa: BLE001 — count + re-raise so the run fails and replays
                if self._metrics is not None:
                    self._metrics.produce_failures.inc()
                log.error("pattern_produce_failed", error=str(exc))
                raise
        return emitted
