package com.acp.patternmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic names — the exact frozen topic contracts (architecture.md "Kafka topics"). Bound
 * from env so nothing is hard-coded, but the defaults are the canonical topic names.
 *
 * @param mined the consumed topic ({@code patterns.mined})
 * @param minedDlq the DLQ for un-processable mined events ({@code patterns.mined.dlq})
 * @param discovered the produced topic for draft patterns ({@code patterns.discovered})
 * @param approved the produced topic for approved patterns ({@code patterns.approved})
 */
@ConfigurationProperties(prefix = "pattern-manager.kafka.topics")
public record KafkaTopicProperties(
        String mined,
        String minedDlq,
        String discovered,
        String approved) {
}
