# syntax=docker/dockerfile:1
# Multi-stage build for the Enrichment Service (Java 17 / Spring Boot 3 / Gradle).
#
# IMPORTANT: build with the REPO ROOT as the context, because this service consumes the frozen
# event-model Java binding via a Gradle composite build (`includeBuild ../../libs/event-model/java`):
#
#   docker build -f services/enrichment/Dockerfile -t acp/enrichment:0.1.0 .
#
# Pinned base image (eclipse-temurin:17-jdk build / :17-jre runtime, per repo toolchain).
# Permissive licenses only.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The frozen contract binding (built as part of the composite build).
COPY libs/event-model /workspace/libs/event-model
# The service sources + Gradle wrapper.
COPY services/enrichment /workspace/services/enrichment

WORKDIR /workspace/services/enrichment
# Build the bootJar (runs unit tests + JaCoCo; integration-tagged tests are excluded from the
# fast unit run and are exercised by the live integration gate against the Compose stack).
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root runtime user.
RUN useradd -r -u 1001 -g root enrichment
USER 1001

COPY --from=build /workspace/services/enrichment/build/libs/*.jar /app/app.jar

# HTTP server: chatter API (/api/v1/sources/{source}/chatter), /openapi.json, Swagger UI,
# /actuator/health (liveness+readiness), /actuator/prometheus.
EXPOSE 8080

# Config is supplied entirely from the environment (no hard-coded URLs/credentials/thresholds);
# see README.md for the full variable set. The per-source rulesets YAML is mounted at
# ENRICHMENT_RULESETS_FILE and the WRITABLE chatter overlay at ENRICHMENT_CHATTER_OVERLAY_FILE
# (so operator chatter promotions are durable across container restarts).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
