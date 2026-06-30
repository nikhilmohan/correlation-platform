"""Test helpers: build a finalized TrailWindow directly from a list of alarms."""

from __future__ import annotations

from acp_event_model import AlarmEvent

from noise_filter.windowing import TrailWindow

from .fixtures import BASE_TIME


def make_window(
    alarms: list[AlarmEvent], *, trail_id: str = "t1", window_size_seconds: int = 600
) -> TrailWindow:
    """Build a window holding ``alarms`` whose window_start <= every alarm's raisedAt."""
    # Choose a bucket so window_start aligns at/just-before BASE_TIME.
    bucket = int(BASE_TIME.timestamp() // window_size_seconds)
    win = TrailWindow(
        trail_id=trail_id,
        bucket_index=bucket,
        window_size_seconds=window_size_seconds,
    )
    for a in alarms:
        win.add(a)
    return win
