package com.acp.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic name for the {@code knowledge.updated} producer — bound from env
 * ({@code KNOWLEDGE_UPDATED_TOPIC}), defaulting to the canonical catalog name.
 *
 * @param updatedTopic the {@code knowledge.updated} topic name
 */
@ConfigurationProperties(prefix = "knowledge.kafka")
public record KafkaTopicProperties(String updatedTopic) {

    public KafkaTopicProperties {
        if (updatedTopic == null || updatedTopic.isBlank()) {
            updatedTopic = "knowledge.updated";
        }
    }
}
