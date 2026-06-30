# syntax=docker/dockerfile:1
#
# Noise Filter service image (multi-stage, pinned python:3.13-slim per CLAUDE.md).
#
# BUILD CONTEXT = repo root (the image needs both the local libs/event-model contract binding
# and the service source):
#
#   docker build -f services/noise-filter/Dockerfile -t acp/noise-filter:dev .
#
# Run mode (per the service design):
#   - default CMD : `python -m noise_filter` → apply yoyo migrations (idempotent), then the Kafka
#                   consume loop (alarms.enriched → transactions.clean) + run-stats/observed-chatter
#                   recording, with /health + /metrics + the read API served on HTTP_PORT.
#   - migrate     : `python -m noise_filter migrate` applies migrations only (idempotent) then exits.

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

# 2) Install the noise-filter package + its third-party deps from pyproject. The yoyo schema
#    migrations ship *inside* the package (src/noise_filter/migrations/*.sql, declared as
#    [tool.setuptools.package-data]), so installing the package puts the SQL in the venv where it
#    is resolved at runtime via importlib.resources — no separate migrations COPY needed.
COPY services/noise-filter/pyproject.toml /build/noise-filter/pyproject.toml
COPY services/noise-filter/README.md /build/noise-filter/README.md
COPY services/noise-filter/src /build/noise-filter/src
RUN pip install /build/noise-filter

# ---- runtime ---------------------------------------------------------------------------
FROM python:3.13-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"

RUN useradd --create-home --uid 10001 noisefilter
WORKDIR /app

COPY --from=builder /opt/venv /opt/venv

USER noisefilter

# /health + /metrics + the run-stats read API are served on HTTP_PORT (default 8080).
EXPOSE 8080

# Default: migrate (yoyo, idempotent) then run the consume loop + HTTP server. Config via env
# (README): Kafka, Knowledge/Topology/Trail-Builder URLs + modes, NOISE_FILTER_DB_URL, LOG_LEVEL.
CMD ["python", "-m", "noise_filter"]
