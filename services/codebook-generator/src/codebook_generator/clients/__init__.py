"""Config-switchable HTTP clients for outbound integration points.

Each client is built against the collaborator's published OpenAPI (not its source). In
unit tests a mocked ``httpx`` transport (respx) backs the client; in integration the same
code points at the live service. Mode/URL come from config — never hard-coded.
"""

from __future__ import annotations
