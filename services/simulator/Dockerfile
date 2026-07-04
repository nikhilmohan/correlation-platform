# syntax=docker/dockerfile:1
#
# Simulator service image (multi-stage, pinned python:3.13-slim per CLAUDE.md toolchain).
#
# BUILD CONTEXT = repo root (the image needs both the local libs/event-model contract binding
# and the service source):
#
#   docker build -f services/simulator/Dockerfile -t acp/simulator:dev .
#
# The build installs the frozen acp-event-model binding (local path dependency) then the
# simulator package into an isolated venv, and copies that venv into a slim non-root runtime.

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

# 2) Install the simulator package from pyproject. The build-time-synced canonical snapshot
#    schema lives INSIDE the package (src/simulator/_vendor/snapshot.schema.json) and is
#    declared as package-data, so a normal install bundles it and snapshot_writer resolves it
#    at runtime via importlib.resources — no source-tree-relative path required.
COPY services/simulator/pyproject.toml /build/simulator/pyproject.toml
COPY services/simulator/README.md /build/simulator/README.md
COPY services/simulator/src /build/simulator/src
RUN pip install "/build/simulator[kafka]"

# ---- runtime ---------------------------------------------------------------------------
FROM python:3.13-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    PYTHONPATH=/app/services/simulator/src \
    HTTP_PORT=8080

# Non-root runtime user.
RUN groupadd --system sim && useradd --system --gid sim --no-create-home --home /app sim
WORKDIR /app

# The installed venv carries acp-event-model + all third-party deps AND the simulator package
# (with its bundled _vendor/snapshot.schema.json). The source tree is also copied so the
# in-place layout still works; either way the schema resolves via importlib.resources.
COPY --from=builder /opt/venv /opt/venv
COPY --from=builder /build/simulator/src /app/services/simulator/src

USER sim

# /health (liveness) + /metrics (Prometheus) are served on HTTP_PORT.
EXPOSE 8080

# Run the simulator service entrypoint (simulator.main:main) from the in-place source tree.
ENTRYPOINT ["python", "-m", "simulator.main"]
