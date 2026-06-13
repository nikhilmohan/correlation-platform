package com.acp.topology.integration;

import com.acp.topology.config.TopologyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Config-switchable client for the Knowledge Service's frozen domain-vocabulary operation
 * {@code GET /domains/{domain}/vocabulary} → {@code { domain, objectTypes[], relations[], version }}.
 *
 * <p>The mode only switches WHERE the request goes (WireMock/Prism stub generated from Knowledge's
 * published OpenAPI in unit tests vs. the live Knowledge base URL in integration); the path and
 * response shape are identical in both. The per-domain vocabulary is cached with a short TTL.
 * Fail-closed: if the operation is unavailable and no non-expired cache entry exists, a
 * {@link VocabularyUnavailableException} is thrown (ingest → 502, no write, no event).
 */
@Component
public class KnowledgeVocabClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVocabClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final TopologyProperties.Knowledge config;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public KnowledgeVocabClient(TopologyProperties properties, ObjectMapper mapper, Clock clock) {
        this.config = properties.getKnowledge();
        this.mapper = mapper;
        this.clock = clock;
        this.restClient = RestClient.builder().baseUrl(config.getBaseUrl()).build();
    }

    /**
     * Resolve the vocabulary for {@code domain}, serving a non-expired cache entry if present.
     *
     * @throws VocabularyUnavailableException if the operation cannot be reached and no live cache
     */
    public DomainVocabulary getVocabulary(String domain) {
        CacheEntry cached = cache.get(domain);
        long now = clock.millis();
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.vocabulary;
        }
        try {
            DomainVocabulary fetched = fetch(domain);
            cache.put(domain, new CacheEntry(fetched,
                    now + config.getVocabTtlSeconds() * 1000L));
            return fetched;
        } catch (RuntimeException e) {
            if (cached != null) {
                log.warn("Knowledge vocabulary fetch failed for domain={}, serving expired cache",
                        domain, e);
                return cached.vocabulary;
            }
            throw new VocabularyUnavailableException(
                    "Knowledge domain-vocabulary unavailable for domain " + domain, e);
        }
    }

    private DomainVocabulary fetch(String domain) {
        String path = config.getVocabPath().replace("{domain}", domain);
        String body = restClient.get().uri(path).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new VocabularyUnavailableException(
                    "empty vocabulary response for domain " + domain);
        }
        try {
            JsonNode root = mapper.readTree(body);
            Set<String> objectTypes = readArray(root.get("objectTypes"));
            Set<String> relations = readArray(root.get("relations"));
            String version = root.hasNonNull("version") ? root.get("version").asText() : null;
            return new DomainVocabulary(domain, objectTypes, relations, version);
        } catch (Exception e) {
            throw new VocabularyUnavailableException(
                    "could not parse vocabulary for domain " + domain, e);
        }
    }

    private static Set<String> readArray(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private record CacheEntry(DomainVocabulary vocabulary, long expiresAtMillis) {
    }
}
