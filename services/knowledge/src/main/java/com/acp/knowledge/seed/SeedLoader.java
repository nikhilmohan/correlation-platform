package com.acp.knowledge.seed;

import com.acp.knowledge.config.SeedProperties;
import com.acp.knowledge.domain.RecordService;
import com.acp.knowledge.domain.RecordType;
import com.acp.knowledge.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Idempotent startup seeder for the Core IP domain pack. Loads {@code seed/core-ip.json} and
 * authors each record through the <b>same validated write path</b> ({@link RecordService#create})
 * so the seed is dogfood-validated. Records that already exist are skipped (idempotent — safe to
 * re-run). Order in the seed file is significant: vocabularies first so cross-record reference
 * validation of templates/fault-origins/trail-policy passes.
 *
 * <p>Onboarding a new domain is records-only: ship another seed file (or author via the API) — no
 * code change.
 */
@Component
@ConditionalOnProperty(name = "knowledge.seed.on-startup", havingValue = "true",
        matchIfMissing = true)
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final RecordService records;
    private final RecordStore store;
    private final ObjectMapper mapper;
    private final SeedProperties properties;

    public SeedLoader(RecordService records, RecordStore store, ObjectMapper mapper,
            SeedProperties properties) {
        this.records = records;
        this.store = store;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        if (!properties.onStartup()) {
            log.info("seed-on-startup disabled; skipping Core IP seed pack");
            return;
        }
        loadPack("seed/core-ip.json");
    }

    /** Load a domain pack resource through the validated write path; skip records already present. */
    public int loadPack(String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("seed pack {} not found; skipping", resourcePath);
            return 0;
        }
        JsonNode pack;
        try (InputStream in = resource.getInputStream()) {
            pack = mapper.readTree(in);
        }
        String domain = pack.path("domain").asText();
        int loaded = 0;
        for (JsonNode rec : pack.path("records")) {
            String recordTypeId = rec.path("recordType").asText();
            String recordId = rec.path("recordId").asText();
            JsonNode payload = rec.path("payload");
            RecordType type = RecordType.byId(recordTypeId)
                    .orElseThrow(() -> new IllegalStateException(
                            "seed references unknown recordType: " + recordTypeId));
            if (store.recordExists(domain, type.id(), recordId)) {
                continue; // already seeded
            }
            records.create(domain, type, recordId, payload, "seed");
            loaded++;
        }
        log.info("seed pack {} loaded ({} new records for domain {})", resourcePath, loaded, domain);
        return loaded;
    }
}
