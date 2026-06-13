# syntax=docker/dockerfile:1
#
# Codebook Generator service image (multi-stage, pinned python:3.13-slim per CLAUDE.md).
#
# BUILD CONTEXT = repo root (the image needs both the local libs/event-model contract binding
# and the service source):
#
#   docker build -f services/codebook-generator/Dockerfile -t acp/codebook-generator:dev .
#
# Two run modes share this image (per the service design):
#   - default CMD  : `python -m codebook_generator`        → apply yoyo migrations, then the
#                     Kafka consumer loop (trails.built → codebook.generated).
#   - API command  : `uvicorn codebook_generator.api:app --host 0.0.0.0 --port 8000`
#                     → the read API (/health + /metrics + codebook read endpoints).
# Compose runs both; the migrate step (`python -m codebook_generator migrate`) is idempotent.

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

# 2) Install the codebook-generator package + its third-party deps from pyproject. The yoyo
#    schema migrations ship *inside* the package (src/codebook_generator/migrations/*.sql, declared
#    as [tool.setuptools.package-data]), so installing the package puts the SQL in the venv where
#    it is resolved at runtime via importlib.resources — no separate migrations COPY needed.
COPY services/codebook-generator/pyproject.toml /build/codebook-generator/pyproject.toml
COPY services/codebook-generator/README.md /build/codebook-generator/README.md
COPY services/codebook-generator/src /build/codebook-generator/src
RUN pip install /build/codebook-generator

# ---- runtime ---------------------------------------------------------------------------
FROM python:3.13-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"

RUN useradd --create-home --uid 10001 codebook
WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
# yoyo migrations are applied on startup; the SQL ships *inside* the installed package (package
# data under /opt/venv/.../codebook_generator/migrations) and is resolved via importlib.resources,
# so no separate /app/migrations copy is required. To override with operator-supplied SQL, mount a
# directory and set CODEBOOK_MIGRATIONS_DIR to it.

USER codebook

# /health + /metrics are served by the FastAPI read API (uvicorn default port 8000).
EXPOSE 8000

# Default: migrate (yoyo, idempotent) then run the Kafka consumer loop. The read API is run
# as an alternate command in Compose (uvicorn codebook_generator.api:app). Config via env (README).
CMD ["python", "-m", "codebook_generator"]
