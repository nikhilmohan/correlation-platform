"""P3 emission scheduling — pace cascades as coherent in-window bursts (M2, cascade-timing fix).

The Correlation Engine windows on **wall-clock arrival** time and lazily opens a
``(trailId, patternId)`` instance on the cascade opener, admitting the rest of the sequence ONLY
while within that pattern's ``sessionWindow.windowMs`` of the opener's arrival. ``LiveReplay`` paces
the wire by sleeping the ``raisedAt`` delta between CONSECUTIVE stream items, so a stream item's
*position and raisedAt gap* directly become its *arrival gap* at the CE.

The earlier design ``sorted(aligned + non_aligned, key=raisedAt)`` GLOBALLY interleaved every
cascade's alarms with other cascades' and with noise across the whole timeline: a single cascade's
opener and its followers no longer arrived contiguously within ``windowMs``, so the CE opened an
instance and then expired it before enough elements arrived -> almost nothing auto-correlated.

This module rebuilds the stream so that:

* each aligned cascade is emitted **contiguously** (opener first, then its window-compressed
  followers) — the whole cascade lands inside ``windowMs`` of its opener at the wire; and
* non-aligned / noise alarms are sprinkled into the **gaps BETWEEN** cascades (never interleaved
  INTO a cascade), by re-timing each noise alarm's ``raisedAt`` to fall strictly after the previous
  cascade's last alarm and strictly before the next cascade's opener.

The result is an ordered ``list[SynthAlarm]`` whose ``raisedAt`` sequence is non-decreasing (so
``LiveReplay`` pacing is coherent) and in which every cascade is an unbroken block.
"""

from __future__ import annotations

import random
from datetime import timedelta

from simulator.engine.models import SynthAlarm
from simulator.synth.aligned_synth import AlignedCascade


def _cascade_ordered(cascade: AlignedCascade) -> list[SynthAlarm]:
    """Return a cascade's alarms opener-first (stable by raisedAt; opener already earliest)."""
    return sorted(cascade.alarms, key=lambda a: a.raised_at)


def build_emission_stream(
    cascades: list[AlignedCascade],
    non_aligned: list[SynthAlarm],
    rng: random.Random,
) -> list[SynthAlarm]:
    """Interleave cascades (as atomic in-window blocks) with noise placed in the between-gaps.

    Cascades are ordered by their opener's ``raisedAt`` (the seeded stagger already separates
    same-(trail,pattern) cascades by strictly more than ``windowMs``). Non-aligned alarms are
    distributed across the gaps between consecutive cascade blocks (and before the first / after the
    last) and re-timed to sit strictly inside their gap — so no noise ever falls within a cascade's
    ``[opener, last]`` span. The returned list is ordered by ``raisedAt`` and keeps each cascade
    contiguous; ``LiveReplay`` then paces it verbatim.
    """
    blocks = sorted((_cascade_ordered(c) for c in cascades), key=lambda b: b[0].raised_at)

    if not blocks:
        # No aligned cascades: just emit the non-aligned stream in its own raisedAt order.
        return sorted(non_aligned, key=lambda a: a.raised_at)

    # Partition the noise budget across (len(blocks) + 1) gaps: before block0, between each pair,
    # and after the last block. Placement is seeded so a fixed P3_RNG_SEED reproduces it.
    n_gaps = len(blocks) + 1
    buckets: list[list[SynthAlarm]] = [[] for _ in range(n_gaps)]
    for alarm in non_aligned:
        buckets[rng.randrange(n_gaps)].append(alarm)

    stream: list[SynthAlarm] = []
    for gap_idx in range(n_gaps):
        prev_block = blocks[gap_idx - 1] if gap_idx > 0 else None
        next_block = blocks[gap_idx] if gap_idx < len(blocks) else None
        _emit_gap_noise(stream, buckets[gap_idx], prev_block, next_block, rng)
        if next_block is not None:
            stream.extend(next_block)
    return stream


def _emit_gap_noise(
    stream: list[SynthAlarm],
    noise: list[SynthAlarm],
    prev_block: list[SynthAlarm] | None,
    next_block: list[SynthAlarm] | None,
    rng: random.Random,
) -> None:
    """Append ``noise`` re-timed strictly inside (prev_block.last, next_block.opener)."""
    if not noise:
        return

    # Lower bound: just after the previous cascade's last alarm (or the earliest cascade opener
    # minus a lead-in when there is no previous block).
    if prev_block is not None:
        lower = prev_block[-1].raised_at + timedelta(milliseconds=1)
    else:
        assert next_block is not None
        lower = next_block[0].raised_at - timedelta(milliseconds=len(noise) + 1)

    # Upper bound: strictly before the next cascade's opener (or open-ended after the last block).
    if next_block is not None:
        upper = next_block[0].raised_at - timedelta(milliseconds=1)
    else:
        upper = lower + timedelta(milliseconds=len(noise) + 1)

    span_ms = (upper - lower).total_seconds() * 1000.0
    if span_ms <= 0:
        # Degenerate gap (should not happen given >windowMs stagger): pack noise tightly at lower.
        span_ms = float(len(noise))
        upper = lower + timedelta(milliseconds=span_ms)

    # Seeded fractional positions inside the gap, then sorted so raisedAt stays non-decreasing.
    fractions = sorted(rng.random() for _ in noise)
    for alarm, frac in zip(noise, fractions, strict=True):
        alarm.raised_at = lower + timedelta(milliseconds=span_ms * frac)
        stream.append(alarm)
