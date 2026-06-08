"""schemaVersion compatibility policy (spec criterion 3).

The initial supported major version is 1. Consumers accept major 1 and reject
any envelope whose major version is >= 2. ``schemaVersion`` is a plain integer
(its value *is* the major version); minor versions are additive and not encoded
separately for the MVP.
"""

from __future__ import annotations

SUPPORTED_MAJOR: int = 1


class SchemaVersionError(ValueError):
    """Raised when an envelope's ``schemaVersion`` major is not supported."""


def check_schema_version(schema_version: int) -> None:
    """Accept major ``1``; reject anything ``>= 2`` (and anything ``< 1``).

    Raises:
        SchemaVersionError: if ``schema_version`` is not the supported major.
    """
    if schema_version != SUPPORTED_MAJOR:
        raise SchemaVersionError(
            f"unsupported schemaVersion {schema_version!r}: "
            f"this binding supports major {SUPPORTED_MAJOR} only "
            f"(reject >= 2)"
        )
