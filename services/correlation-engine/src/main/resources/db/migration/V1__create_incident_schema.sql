-- V1 — create the owned schema. The Correlation Engine owns exactly the `incident` schema in the
-- shared PostgreSQL; Flyway is scoped to it (spring.flyway.schemas=incident,
-- default-schema=incident), so nothing is created in `public`.
CREATE SCHEMA IF NOT EXISTS incident;
