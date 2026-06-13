"""Codebook Generator Service.

Compiles the forward-propagation codebook (candidate root-cause instance to predicted
symptom signature, tagged to trails) on ``trails.built``, persists it to the owned
PostgreSQL ``codebook`` schema, emits ``codebook.generated``, and serves the codebook
read API (native scenarios + the Correlation-Engine ``trail-signatures`` projection).

The service is domain-agnostic: fault-origin types, propagation templates, and the
alarm-type vocabulary are read from the Knowledge Service at runtime, parameterized by
``domain``. No Core IP specifics are hard-coded.
"""

from __future__ import annotations

__version__ = "0.1.0"
