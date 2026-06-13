#!/usr/bin/env python3
"""Sync the canonical topology snapshot schema into the Simulator's vendor cache.

OQ-4 resolution (design): the topology snapshot schema has ONE canonical source,
``services/topology/schema/snapshot.schema.json``, owned by the Topology Service. The
Simulator keeps **no independent copy** — it validates against that same file. Because the
Topology branch may not be merged into the Simulator's working tree, this script *syncs*
(copies verbatim) the canonical file into ``services/simulator/_vendor/snapshot.schema.json``
as a build-time cache, recording provenance. It NEVER re-authors the schema. Run it whenever
the canonical schema is available in the checkout.

Usage:
    python scripts/sync_schema.py [--canonical PATH]
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

_SVC_DIR = Path(__file__).resolve().parents[1]
_DEFAULT_CANONICAL = _SVC_DIR.parents[1] / "services" / "topology" / "schema" / "snapshot.schema.json"
_VENDOR = _SVC_DIR / "_vendor" / "snapshot.schema.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--canonical", type=Path, default=_DEFAULT_CANONICAL)
    args = parser.parse_args()
    if not args.canonical.exists():
        print(
            f"canonical schema not found at {args.canonical}; "
            f"keeping existing vendor cache {_VENDOR}",
            file=sys.stderr,
        )
        return 0 if _VENDOR.exists() else 1
    _VENDOR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.canonical, _VENDOR)
    print(f"synced {args.canonical} -> {_VENDOR}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
