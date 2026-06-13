"""Named, overridable demo profiles — p1-demo / p2-demo / p3-demo (fix B3, criterion 32).

A profile is a bundle of *overridable* defaults selected by ``DEMO_PROFILE``. It sets
``TOTAL_ALARMS``, the scenario set, node/site/interface counts and noise/background fractions
to repeatably hit the demo numbers; any individual env var still overrides the profile value
(profiles are defaults, not locks). The profile values are applied to ``Settings`` only where
the operator did not supply the corresponding env var.
"""

from __future__ import annotations

from typing import Any

# Each profile is a dict of Settings field-name -> value. Only fields the operator did not set
# explicitly are taken from the profile (see settings.apply_profile).
DEMO_PROFILES: dict[str, dict[str, Any]] = {
    "p1-demo": {
        "site_count": 10,
        "topology_node_count": 50,
        "interfaces_per_port": 2,
        "igp_area_count": 3,
    },
    "p2-demo": {
        "topology_node_count": 50,
        "site_count": 10,
        "interfaces_per_port": 2,
        "igp_area_count": 3,
        "total_alarms": 1000,
        "noise_rate": 0.2,
        "hard_noise_fraction": 0.4,
        "background_fraction": 0.25,
    },
    "p3-demo": {
        "topology_node_count": 50,
        "site_count": 10,
        "interfaces_per_port": 2,
        "igp_area_count": 3,
        "total_alarms": 500,
        "noise_rate": 0.2,
        "hard_noise_fraction": 0.4,
        "background_fraction": 0.25,
        "pacing_multiplier": 1.0,
    },
}

PROFILE_NAMES: tuple[str, ...] = tuple(DEMO_PROFILES.keys())
