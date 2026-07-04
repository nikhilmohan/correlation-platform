# syntax=docker/dockerfile:1
# Multi-stage build for the Correlation Engine (Java 17 / Spring Boot 3 / Gradle).
#
# IMPORTANT: build with the REPO ROOT as the context, because this service consumes the frozen
# event-model Java binding via a Gradle composite build (`includeBuild ../../libs/event-model/java`):
#
#   docker build -f services/correlation-engine/Dockerfile -t acp/correlation-engine:0.1.0 .
#
# Pinned base image (eclipse-temurin:17-jdk, per repo toolchain). Permissive licenses only.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The frozen contract binding (built as part of the composite build).
COPY libs/event-model /workspace/libs/event-model
# The service sources + Gradle wrapper.
COPY services/correlation-engine /workspace/services/correlation-engine

WORKDIR /workspace/services/correlation-engine
# Build the bootJar (excludes Testcontainers integration tests by default; runs unit tests + JaCoCo).
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root runtime user.
RUN useradd -r -u 1001 -g root correlation-engine
USER 1001

COPY --from=build /workspace/services/correlation-engine/build/libs/*.jar /app/app.jar

# HTTP API + Actuator (/actuator/health liveness+readiness, /actuator/prometheus metrics).
EXPOSE 8080

# Config is supplied entirely from the environment (no hard-coded URLs/credentials/thresholds);
# see README.md for the full variable set.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
