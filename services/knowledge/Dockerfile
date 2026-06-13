# syntax=docker/dockerfile:1
#
# Knowledge Service — multi-stage build (Java 17, pinned Temurin).
#
# IMPORTANT: build with the REPOSITORY ROOT as the build context, because this service's Gradle
# build is a composite build that includes `libs/event-model/java` (the frozen event-model Java
# binding). From the repo root:
#
#   docker build -f services/knowledge/Dockerfile -t acp/knowledge:0.1.0 .
#
# (The docker-compose entry sets `context: .` + `dockerfile: services/knowledge/Dockerfile`.)

# ---- Build stage -------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The composite build needs both the service and the event-model binding it includes.
COPY libs/event-model /workspace/libs/event-model
COPY services/knowledge /workspace/services/knowledge

WORKDIR /workspace/services/knowledge
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon clean bootJar -x test

# ---- Runtime stage -----------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root runtime user.
RUN useradd --system --uid 10001 --no-create-home knowledge
USER knowledge

COPY --from=build /workspace/services/knowledge/build/libs/*.jar /app/app.jar

# /health + /metrics (Actuator/Micrometer-Prometheus), config from env only.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
