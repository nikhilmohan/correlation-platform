package com.acp.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Knowledge Service — the authoritative, versioned store for all authored domain knowledge.
 *
 * <p>Stores eight record types under one unified record model (propagationTemplate,
 * faultOriginType, trailPolicy, modelParams, objectTypeVocabulary, edgeRelationVocabulary,
 * attributeCatalogue, alarmTypeVocabulary), validates each write against the per-recordType
 * JSON Schema + cross-record references, versions immutably, serves current/pinned versions via
 * a versioned API, and emits {@code knowledge.updated} on every persisted change. Consumes
 * nothing from Kafka.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
