# syntax=docker/dockerfile:1
# TBD: minimal scaffold. Fill in once the Gradle build exists (kickoff build stage).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
# COPY . .
# RUN ./gradlew --no-daemon build

FROM eclipse-temurin:17-jre
WORKDIR /app
# COPY --from=build /workspace/build/libs/*.jar app.jar
# EXPOSE 8080        # /actuator/health + /actuator/prometheus
# ENTRYPOINT ["java", "-jar", "app.jar"]
