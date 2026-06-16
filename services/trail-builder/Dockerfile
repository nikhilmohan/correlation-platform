# syntax=docker/dockerfile:1
#
# Trail Builder service image (multi-stage, pinned python:3.13-slim per CLAUDE.md toolchain).
#
# BUILD CONTEXT = repo root (the image needs both the local libs/event-model contract binding
# and the service source):
#
#   docker build -f services/trail-builder/Dockerfile -t acp/trail-builder:dev .
#
# The build installs the frozen acp-event-model binding (local path dependency) then the
# trail-builder package into an isolated venv, and copies that venv into a slim non-root runtime.

# ---- builder ---------------------------------------------------------------------------
FROM python:3.13-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"

WORKDIR /build

RUN python -m venv "$VIRTUAL_ENV"

# 1) Install the frozen contract binding first (local path dependency, own layer for caching).
COPY libs/event-model/python /build/event-model
RUN pip install /build/event-model

# 2) Install the trail-builder package + its third-party deps from pyproject. The Alembic
#    migrations ship INSIDE the package (src/trailbuilder/migrations, declared as package-data),
#    so they are installed into the venv with the package and resolved at runtime via
#    importlib.resources — no separate copy needed.
COPY services/trail-builder/pyproject.toml /build/trail-builder/pyproject.toml
COPY services/trail-builder/README.md /build/trail-builder/README.md
COPY services/trail-builder/src /build/trail-builder/src
RUN pip install /build/trail-builder

# ---- runtime ---------------------------------------------------------------------------
FROM python:3.13-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"

# Non-root runtime user.
RUN useradd --create-home --uid 10001 trail
WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
# Alembic migrations ship INSIDE the installed package (site-packages/trailbuilder/migrations)
# and are resolved at startup via importlib.resources — no separate /app copy needed.

USER trail

# /health + /metrics are served by the FastAPI app (runtime serves on 0.0.0.0:8000).
EXPOSE 8000

# Entrypoint: the service runtime — CREATE SCHEMA + Alembic migrate, then the Kafka consumer
# (topology.changed) + the HTTP query API. Config is supplied via environment variables (README).
CMD ["python", "-m", "trailbuilder"]
