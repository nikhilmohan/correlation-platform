# syntax=docker/dockerfile:1
# Multi-stage build for the Alarm Manager (Java 17 / Spring Boot 3 / Gradle).
#
# IMPORTANT: build with the REPO ROOT as the context, because this service consumes the frozen
# event-model Java binding via a Gradle composite build (`includeBuild ../../libs/event-model/java`):
#
#   docker build -f services/alarm-manager/Dockerfile -t acp/alarm-manager:0.1.0 .
#
# Pinned base image (eclipse-temurin:17-jdk, per repo toolchain). Permissive licenses only.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The frozen contract binding (built as part of the composite build).
COPY libs/event-model /workspace/libs/event-model
# The service sources + Gradle wrapper.
COPY services/alarm-manager /workspace/services/alarm-manager

WORKDIR /workspace/services/alarm-manager
# Build the bootJar (Testcontainers integration tests are @Tag("integration") and excluded from
# the default bootJar build; unit/contract tests + JaCoCo run in CI, not in the image build).
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root runtime user.
RUN useradd -r -u 1001 -g root alarmmanager
USER 1001

COPY --from=build /workspace/services/alarm-manager/build/libs/*.jar /app/app.jar

# HTTP API + Actuator (/actuator/health liveness+readiness, /actuator/prometheus metrics,
# /openapi.json, /alarms).
EXPOSE 8080

# Config is supplied entirely from the environment (no hard-coded URLs/credentials/thresholds);
# see README.md for the full variable set.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
