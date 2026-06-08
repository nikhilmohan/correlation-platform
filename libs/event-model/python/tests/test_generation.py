"""Criterion 2 (Python side): single-source propagation.

Adding an optional field to a copy of a payload schema and regenerating yields
the new field on the Pydantic model with no hand edits. The test drives the
real generator (``scripts/generate.py``'s bundle + datamodel-codegen) on a temp
schema dir and inspects the generated artifact.

Skipped if ``datamodel-code-generator`` is not installed (it is a dev-only
dependency; CI installs the dev extras).
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(
    importlib.util.find_spec("datamodel_code_generator") is None,
    reason="datamodel-code-generator (dev extra) not installed",
)

PKG_DIR = Path(__file__).resolve().parent.parent
SCHEMA_DIR = PKG_DIR.parent / "schema"


def test_added_schema_field_appears_in_model(tmp_path: Path) -> None:
    # 1. Copy the source schema tree into a temp dir.
    import shutil

    temp_schema = tmp_path / "schema"
    shutil.copytree(SCHEMA_DIR, temp_schema)

    # 2. Add an optional field to AlarmEvent (not in required[]).
    alarm_path = temp_schema / "payloads" / "AlarmEvent.schema.json"
    alarm = json.loads(alarm_path.read_text())
    alarm["properties"]["operatorNote"] = {
        "type": "string",
        "description": "added-by-test optional field",
    }
    alarm_path.write_text(json.dumps(alarm, indent=2))

    # 3. Build the bundle from the modified tree and regenerate to a temp module.
    sys.path.insert(0, str(PKG_DIR / "scripts"))
    try:
        import importlib

        gen = importlib.import_module("generate")
        importlib.reload(gen)
        gen.SCHEMA_DIR = temp_schema
        gen.PAYLOADS_DIR = temp_schema / "payloads"
        bundle = gen.build_bundle()
    finally:
        sys.path.remove(str(PKG_DIR / "scripts"))

    bundle_path = tmp_path / "bundle.json"
    bundle_path.write_text(json.dumps(bundle))
    out_module = tmp_path / "regenerated.py"

    subprocess.run(
        [
            sys.executable,
            "-m",
            "datamodel_code_generator",
            "--input",
            str(bundle_path),
            "--input-file-type",
            "jsonschema",
            "--output",
            str(out_module),
            "--output-model-type",
            "pydantic_v2.BaseModel",
            "--target-python-version",
            "3.12",
            "--use-standard-collections",
            "--use-union-operator",
        ],
        check=True,
    )

    # 4. The new field is present in the regenerated model — no manual edits.
    generated_source = out_module.read_text()
    assert "operatorNote" in generated_source, "added optional field did not propagate to the model"
