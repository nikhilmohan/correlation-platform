"""SQLAlchemy ``MetaData`` pinned to the owned ``trailbuilder`` schema.

Every table below inherits ``schema='trailbuilder'`` so it is created and
addressed as ``trailbuilder.<table>`` — never defaulting into ``public`` and
colliding on the shared DB.
"""

from __future__ import annotations

from sqlalchemy import MetaData

SCHEMA = "trailbuilder"

metadata = MetaData(schema=SCHEMA)
