"""Write the service's OpenAPI 3.1 document to ``services/codebook-generator/openapi.json``.

A CI check asserts the checked-in file equals the live ``/openapi.json``. OpenAPI generation
needs only the route/model metadata, so a store is not required.
"""

from __future__ import annotations

import json
from pathlib import Path

from codebook_generator.api import create_app


def render_openapi() -> dict:
    """Return the OpenAPI document the running service would publish."""
    app = create_app(store=None)
    return app.openapi()


def main() -> None:
    out = Path(__file__).resolve().parents[1] / "openapi.json"
    out.write_text(json.dumps(render_openapi(), indent=2, sort_keys=True) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
