#!/usr/bin/env python3
"""Generate the Pydantic v2 binding from the single-source JSON Schema.

This is the *only* way the model classes (`Envelope` + the nine payloads +
`Provenance`) are produced — they are never hand-authored (spec criterion 2:
single source of truth). A field change in `schema/*.json` propagates to the
binding on the next run of this script with zero manual edits.

How it works:

1. Read the authoritative source files under `schema/` (envelope, the nine
   payloads, and the shared `common/managedObjectId.schema.json`).
2. Bundle them into one in-memory JSON Schema document whose top-level
   `$defs` hold every payload + the envelope. Cross-file `$ref`s (e.g.
   AlarmEvent -> managedObjectId) are rewritten to local `#/$defs/...` refs.
   The bundle is *derived* from the source files — nothing is hand-authored —
   so the single-source guarantee holds; bundling just lets the code generator
   emit one flat module instead of a mirror-of-the-tree package.
3. Run `datamodel-codegen` over the bundle, writing a single flat module to
   `src/acp_event_model/_generated.py`.

The envelope's `payload` is intentionally a generic object in the schema; the
hand-written `registry`/`codec` layer performs the `type` -> payload-class
dispatch (the discriminator), not the envelope schema. This keeps the wire
contract identical across both bindings.

Usage (from the python package dir, with the dev extras installed):

    python scripts/generate.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
PKG_DIR = HERE.parent  # libs/event-model/python
SCHEMA_DIR = PKG_DIR.parent / "schema"
PAYLOADS_DIR = SCHEMA_DIR / "payloads"
OUTPUT = PKG_DIR / "src" / "acp_event_model" / "_generated.py"

# titles of the shared common/* schemas -> become $defs keys (one model each,
# referenced by every payload that $refs them — keeps the binding single-source
# and avoids duplicate per-payload inline models).
MANAGED_OBJECT_ID_DEF = "ManagedObjectId"
SESSION_WINDOW_DEF = "SessionWindow"

PAYLOAD_FILES = [
    "AlarmEvent.schema.json",
    "TopologyChangedEvent.schema.json",
    "TrailsBuiltEvent.schema.json",
    "CodebookGeneratedEvent.schema.json",
    "TransactionEvent.schema.json",
    "PatternMinedEvent.schema.json",
    "PatternDiscoveredEvent.schema.json",
    "PatternApprovedEvent.schema.json",
    "CorrelationResultEvent.schema.json",
    "KnowledgeUpdatedEvent.schema.json",
    "AlarmStatusChange.schema.json",
]


def _load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text())


def _rewrite_refs(node: Any) -> Any:
    """Rewrite cross-file $refs to local #/$defs/<Name> refs in the bundle."""
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                if "managedObjectId.schema.json" in value:
                    out[key] = f"#/$defs/{MANAGED_OBJECT_ID_DEF}"
                elif "sessionWindow.schema.json" in value:
                    out[key] = f"#/$defs/{SESSION_WINDOW_DEF}"
                else:
                    out[key] = value
            else:
                out[key] = _rewrite_refs(value)
        # Strip per-file metadata that does not belong inside a $defs entry.
        out.pop("$schema", None)
        out.pop("$id", None)
        return out
    if isinstance(node, list):
        return [_rewrite_refs(item) for item in node]
    return node


def build_bundle() -> dict[str, Any]:
    defs: dict[str, Any] = {}

    moi = _load(SCHEMA_DIR / "common" / "managedObjectId.schema.json")
    defs[MANAGED_OBJECT_ID_DEF] = _rewrite_refs(moi)

    session_window = _load(SCHEMA_DIR / "common" / "sessionWindow.schema.json")
    defs[SESSION_WINDOW_DEF] = _rewrite_refs(session_window)

    for filename in PAYLOAD_FILES:
        schema = _load(PAYLOADS_DIR / filename)
        name = schema["title"]
        defs[name] = _rewrite_refs(schema)

    envelope = _load(SCHEMA_DIR / "envelope.schema.json")
    defs["Envelope"] = _rewrite_refs(envelope)

    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "title": "AcpEventModel",
        "$defs": defs,
    }


def main() -> int:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    bundle = build_bundle()

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tmp:
        json.dump(bundle, tmp, indent=2)
        tmp_path = Path(tmp.name)

    cmd = [
        sys.executable,
        "-m",
        "datamodel_code_generator",
        "--input",
        str(tmp_path),
        "--input-file-type",
        "jsonschema",
        "--output",
        str(OUTPUT),
        "--output-model-type",
        "pydantic_v2.BaseModel",
        # datamodel-code-generator (0.25.x) caps at 3.12; the generated syntax
        # (PEP 604 unions, standard collections) is fully valid on our 3.13 runtime.
        "--target-python-version",
        "3.12",
        "--use-standard-collections",
        "--use-union-operator",
        "--use-schema-description",
        "--disable-timestamp",
        "--use-double-quotes",
        "--collapse-root-models",
    ]
    print("running:", " ".join(cmd))
    try:
        proc = subprocess.run(cmd, cwd=str(PKG_DIR))
    finally:
        tmp_path.unlink(missing_ok=True)
    if proc.returncode != 0:
        return proc.returncode
    print(f"wrote {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
