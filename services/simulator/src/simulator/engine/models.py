"""Internal synthesis models (not wire types — those come from ``acp_event_model``).

These carry the synthesizer's intermediate state: each generated alarm (with its scenario
linkage + noise tagging) and the ground-truth label. The wire ``AlarmEvent`` is constructed
from :class:`SynthAlarm` at replay time via the frozen event-model binding.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class SynthAlarm:
    """One synthesized alarm before it becomes a wire ``AlarmEvent``."""

    alarm_id: str
    managed_object_id: str
    alarm_type: str
    event_type: str
    probable_cause: str
    perceived_severity: str
    raised_at: datetime
    state: str = "raised"
    trace_id: str = ""
    # synthesis bookkeeping (not serialized to the wire payload)
    scenario_id: str | None = None
    is_root: bool = False
    is_noise: bool = False
    noise_class: str | None = None
    is_hard_noise: bool = False
    is_background: bool = False


@dataclass
class GroundTruthLabel:
    """Ground-truth label per injected scenario (the eval oracle record)."""

    scenario_id: str
    scenario_type: str
    root_cause: str  # root alarmId
    root_cause_managed_object_id: str
    root_cause_alarm_type: str
    children: list[str] = field(default_factory=list)
