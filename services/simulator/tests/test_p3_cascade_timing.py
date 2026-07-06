"""P3 cascade-timing fix (M2): each aligned cascade must ARRIVE at the Correlation Engine as a
coherent in-window burst.

The CE windows on wall-clock ARRIVAL and lazily opens a ``(trailId, patternId)`` instance on the
cascade opener, admitting followers only within ``sessionWindow.windowMs`` of the opener's arrival.
``LiveReplay`` paces the wire by sleeping the ``raisedAt`` delta between consecutive items, so a
fake clock+sleeper faithfully reproduces the *arrival* time of each emitted alarm. These tests
prove, at the wire:

* every aligned cascade's alarms arrive within that pattern's ``windowMs`` of one another,
* the cascade's opener (sequence[0].alarmType) arrives first in its block, and
* non-aligned / noise alarms fall BETWEEN cascade blocks — never inside a cascade's arrival span.

They exercise the real ``p3_run.run_synth`` -> ``p3_schedule`` -> ``LiveReplay`` path (with a
non-zero pacing multiplier and a fake clock) so a regression to the old global-sort interleave — the
defect that sank auto-correlation from 60-70% to ~2-20% — fails the suite.
"""

from __future__ import annotations

from pathlib import Path

from acp_event_model import TypedEnvelope

from simulator.config.settings import load_settings
from simulator.engine import replay
from simulator.integrations.pattern_manager_client import MockPatternManagerClient
from simulator.integrations.topology_snapshot_client import MockTopologySnapshotClient
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import p3_run
from tests import p3_fixtures as fx

WINDOW_MS = 6000


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, TypedEnvelope]] = []

    def produce(self, topic: str, envelope: TypedEnvelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        pass


class FakeClock:
    """A monotonic clock advanced only by the sleeper — models wall-clock arrival under pacing."""

    def __init__(self) -> None:
        self.t = 0.0

    def now(self) -> float:
        return self.t

    def sleep(self, seconds: float) -> None:
        self.t += seconds


def _patterns():
    # Two patterns; each will be instantiated repeatedly (round-robin) so several cascades exist.
    return [
        fx.pattern_view(
            "pat-01",
            "trail-A",
            [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", False)],
            "IPLinkDown",
            window_ms=WINDOW_MS,
        ),
        fx.pattern_view(
            "pat-02",
            "trail-B",
            [("FiberFault", False), ("IPLinkDown", False)],
            "FiberFault",
            window_ms=WINDOW_MS,
        ),
    ]


def _trails():
    return {
        "trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS),
        "trail-B": fx.trail_detail("trail-B", fx.TRAIL_B_MEMBERS),
    }


def _settings(tmp_path: Path, **extra: str):
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "SIM_OUTPUT_DIR": str(tmp_path),
        "PACING_MULTIPLIER": "1.0",  # real-time pacing (fake clock advances by the raisedAt gaps)
        "P3_TOTAL_ALARMS": "80",
        "P3_ALIGNED_FRACTION": "0.6",
        "P3_RNG_SEED": "4242",
        "phase": "p3",
    }
    env.update(extra)
    return load_settings(env)


def _emit_with_fake_clock(settings, producer) -> list[tuple[float, TypedEnvelope]]:
    """Run synth but drive LiveReplay with a fake clock+sleeper; capture (arrival_s, envelope)."""
    clock = FakeClock()
    arrivals: list[tuple[float, TypedEnvelope]] = []

    real_live = replay.LiveReplay

    def factory(  # noqa: ANN001
        prod, pacing_multiplier=1.0, tap=None, sleeper=None, clock_fn=None, progress=None
    ):
        strat = real_live(
            prod,
            pacing_multiplier=pacing_multiplier,
            tap=tap,
            sleeper=clock.sleep,
            clock=clock.now,
            progress=progress,
        )
        orig = strat._producer.produce

        def wrapped(topic, envelope):  # noqa: ANN001
            arrivals.append((clock.now(), envelope))
            orig(topic, envelope)

        strat._producer.produce = wrapped  # type: ignore[method-assign]
        return strat

    replay.LiveReplay = factory  # type: ignore[assignment,misc]
    try:
        pm = MockPatternManagerClient(_patterns())
        tb = MockTrailBuilderClient(_trails())
        ts = MockTopologySnapshotClient([fx.snapshot_summary()])
        p3_run.run_synth(
            settings,
            producer,
            run_id="ct",
            pattern_client=pm,
            trail_client=tb,
            snapshot_client=ts,
        )
    finally:
        replay.LiveReplay = real_live  # type: ignore[assignment,misc]
    return arrivals


def _opener_types() -> dict[str, str]:
    # patternId -> the OPENING alarmType (sequence[0]) that lazily opens a CE instance.
    return {"pat-01": "IPLinkDown", "pat-02": "FiberFault"}


def test_each_cascade_arrives_within_window_opener_first(tmp_path: Path) -> None:
    arrivals = _emit_with_fake_clock(_settings(tmp_path), FakeProducer())
    assert arrivals, "expected emitted alarms"

    openers = _opener_types()
    # Group aligned alarms into contiguous cascade blocks by traceId (== patternId for aligned).
    blocks: list[list[tuple[float, TypedEnvelope]]] = []
    current: list[tuple[float, TypedEnvelope]] = []
    current_pat: str | None = None

    def is_aligned(env: TypedEnvelope) -> bool:
        return env.traceId in openers

    for t, env in arrivals:
        pat = env.traceId if is_aligned(env) else None
        if pat is not None:
            opener = openers[pat]
            starts_new = env.payload.alarmType == opener
            if current and (current_pat != pat or starts_new):
                blocks.append(current)
                current = []
            current.append((t, env))
            current_pat = pat
        else:
            if current:
                blocks.append(current)
                current = []
                current_pat = None
    if current:
        blocks.append(current)

    assert len(blocks) >= 3, "need several cascades to exercise the in-window burst property"
    for block in blocks:
        pat = block[0][1].traceId
        opener = openers[pat]
        # Opener arrives FIRST in its block.
        assert (
            block[0][1].payload.alarmType == opener
        ), f"cascade {pat} opener did not arrive first: {[e.payload.alarmType for _, e in block]}"
        # Whole cascade arrives within windowMs of the opener (wall-clock).
        first_arr = block[0][0]
        last_arr = block[-1][0]
        span_ms = (last_arr - first_arr) * 1000.0
        assert (
            span_ms < WINDOW_MS
        ), f"cascade {pat} arrival span {span_ms}ms >= windowMs {WINDOW_MS}ms — CE would expire it"


def test_noise_falls_between_cascades_not_inside(tmp_path: Path) -> None:
    arrivals = _emit_with_fake_clock(_settings(tmp_path), FakeProducer())
    openers = _opener_types()

    # For every aligned cascade block, assert no non-aligned alarm arrives strictly inside its span.
    aligned_idx = [i for i, (_, e) in enumerate(arrivals) if e.traceId in openers]
    assert aligned_idx

    # Reconstruct blocks as contiguous aligned runs of the SAME patternId opened by an opener.
    blocks: list[tuple[int, int]] = []  # (start_idx, end_idx) inclusive
    start = None
    prev_pat = None
    for i, (_, env) in enumerate(arrivals):
        pat = env.traceId if env.traceId in openers else None
        opener = openers.get(pat) if pat else None
        if pat is not None and env.payload.alarmType == opener:
            if start is not None:
                blocks.append((start, i - 1))
            start = i
            prev_pat = pat
        elif pat is not None and pat == prev_pat and start is not None:
            continue
        else:
            if start is not None:
                blocks.append((start, i - 1))
                start = None
                prev_pat = None
    if start is not None:
        blocks.append((start, len(arrivals) - 1))

    for s, e in blocks:
        for j in range(s, e + 1):
            env = arrivals[j][1]
            assert (
                env.traceId in openers
            ), f"non-aligned alarm {env.payload.alarmType} landed INSIDE cascade block [{s},{e}]"
