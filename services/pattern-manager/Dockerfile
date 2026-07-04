# syntax=docker/dockerfile:1
#
# Pattern Manager — multi-stage build (Java 17 / Spring Boot 3 / Gradle, pinned Temurin).
#
# IMPORTANT: build with the REPOSITORY ROOT as the build context, because this service's Gradle
# build is a composite build that includes `libs/event-model/java` (the frozen event-model Java
# binding). From the repo root:
#
#   docker build -f services/pattern-manager/Dockerfile -t acp/pattern-manager:0.1.0 .
#
# (The docker-compose entry sets `context: .` + `dockerfile: services/pattern-manager/Dockerfile`.)

# ---- Build stage -------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The composite build needs both the service and the event-model binding it includes.
COPY libs/event-model /workspace/libs/event-model
COPY services/pattern-manager /workspace/services/pattern-manager

WORKDIR /workspace/services/pattern-manager
# Build the bootJar (excludes @Tag("integration") Testcontainers tests; runs unit tests + JaCoCo).
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon clean bootJar

# ---- Runtime stage -----------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root runtime user.
RUN useradd -r -u 1001 -g root patternmanager
USER 1001

COPY --from=build /workspace/services/pattern-manager/build/libs/*.jar /app/app.jar

# HTTP API + Actuator (/actuator/health liveness+readiness, /actuator/prometheus metrics).
EXPOSE 8080

# Config is supplied entirely from the environment (no hard-coded URLs/credentials/thresholds);
# see README.md for the full PATTERN_MANAGER_* / SERVER_* / SPRING_* variable set.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
