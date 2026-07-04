package com.acp.correlationengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment-bound configuration for the Correlation Engine. All collaborator base URLs, the
 * {@code mock|real} integration toggle, topic names, and tuning knobs come from the environment —
 * no hard-coded URLs, credentials, or thresholds (match-quality/conflict thresholds are sourced
 * from the Knowledge Service; the per-pattern session window comes from the pattern itself).
 *
 * @param integrationMode {@code mock} (unit) or {@code real} (integration/prod). Selects whether
 *     the outbound clients point at a stub or the real Compose services.
 * @param patternManagerBaseUrl base URL of the Pattern Manager read API.
 * @param codebookGeneratorBaseUrl base URL of the Codebook Generator API.
 * @param knowledgeBaseUrl base URL of the Knowledge Service API.
 * @param knowledgeDomain the Knowledge domain (default {@code core-ip}).
 * @param knowledgeRefreshMs Knowledge params cache TTL (ms).
 * @param expiryTickMs the wall-clock cadence at which session-expiry + uncovered-buffer decode runs.
 * @param rcaEvalMode when {@code on}, {@code GET /stats.rcaAccuracy} may be computed against a
 *     wired labels oracle; {@code off} (production) leaves it {@code null}.
 * @param topics the in/out Kafka topic names (frozen contract).
 */
@ConfigurationProperties(prefix = "correlation-engine")
public record CorrelationEngineProperties(
        String integrationMode,
        String patternManagerBaseUrl,
        String codebookGeneratorBaseUrl,
        String knowledgeBaseUrl,
        String knowledgeDomain,
        long knowledgeRefreshMs,
        long expiryTickMs,
        String rcaEvalMode,
        Topics topics) {

    public CorrelationEngineProperties {
        if (integrationMode == null || integrationMode.isBlank()) {
            integrationMode = "real";
        }
        if (knowledgeDomain == null || knowledgeDomain.isBlank()) {
            knowledgeDomain = "core-ip";
        }
        if (knowledgeRefreshMs <= 0) {
            knowledgeRefreshMs = 60_000L;
        }
        if (expiryTickMs <= 0) {
            expiryTickMs = 1_000L;
        }
        if (rcaEvalMode == null || rcaEvalMode.isBlank()) {
            rcaEvalMode = "off";
        }
        if (topics == null) {
            topics = Topics.defaults();
        }
    }

    public boolean isMock() {
        return "mock".equalsIgnoreCase(integrationMode);
    }

    public boolean isRcaEvalOn() {
        return "on".equalsIgnoreCase(rcaEvalMode);
    }

    /** The frozen in/out topic names (contract). */
    public record Topics(
            String alarmsPersistedLive,
            String patternsApproved,
            String codebookGenerated,
            String correlationResults,
            String alarmsStatusChanged) {

        public static Topics defaults() {
            return new Topics(
                    "alarms.persisted.live",
                    "patterns.approved",
                    "codebook.generated",
                    "correlation.results",
                    "alarms.status.changed");
        }

        public String dlqFor(String topic) {
            return topic + ".dlq";
        }
    }
}
