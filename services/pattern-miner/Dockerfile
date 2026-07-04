# syntax=docker/dockerfile:1
#
# Pattern Miner service image (multi-stage, pinned python:3.13-slim per CLAUDE.md) + a container-only
# Spark/PySpark runtime (Spark is NOT installed on the host; all Spark execution — and the
# Spark-marked tests — run inside this image, per CLAUDE.md and the design).
#
# BUILD CONTEXT = repo root (the image needs both the local libs/event-model contract binding and
# the service source):
#
#   docker build -f services/pattern-miner/Dockerfile -t acp/pattern-miner:dev .
#
# Run mode (per the service design):
#   - default CMD : `python -m pattern_miner` → run the consume→mine→produce loop
#                   (transactions.clean → PrefixSpan → patterns.mined) CONCURRENTLY with the uvicorn
#                   HTTP server serving /health + /metrics on HTTP_PORT. Stateless: no owned store.
#   - tests       : `pytest -m spark` exercises the real Spark MLlib PrefixSpan engine in local[*].

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

# 2) Install the pattern-miner package + its third-party deps INCLUDING the container-only Spark
#    extra (PySpark). PySpark ships its own bundled Spark distribution, so no separate Spark
#    tarball is needed — only a JRE at runtime.
COPY services/pattern-miner/pyproject.toml /build/pattern-miner/pyproject.toml
COPY services/pattern-miner/README.md /build/pattern-miner/README.md
COPY services/pattern-miner/src /build/pattern-miner/src
RUN pip install "/build/pattern-miner[spark]"

# ---- runtime ---------------------------------------------------------------------------
FROM python:3.13-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"

# Spark/PySpark needs a JRE. Install the slim headless JRE (permissive) from Debian.
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 patternminer
WORKDIR /app

COPY --from=builder /opt/venv /opt/venv

USER patternminer

# /health + /metrics are served on HTTP_PORT (default 8080).
EXPOSE 8080

# Default: run the consume→mine→produce loop + HTTP server concurrently.
# Config via env (README): Kafka topics/bootstrap, Knowledge base URL + domain + record id + mode,
# SPARK_MASTER, MINING_ENGINE, HTTP_PORT, LOG_LEVEL. NO mining-threshold env vars — those come
# only from the Knowledge Service.
CMD ["python", "-m", "pattern_miner"]
