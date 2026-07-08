package com.acp.patternmanager.seed;

import com.acp.patternmanager.config.PatternSeedProperties;
import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.DerivedSessionWindow.WindowType;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.enrichment.SampleAlarm;
import com.acp.patternmanager.event.PatternEventPublisher;
import com.acp.patternmanager.store.PatternStoreService;
import com.acp.patternmanager.store.UuidV5;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Idempotent startup seeder for pre-approved Core IP cascade patterns (mirrors Knowledge's
 * {@code SeedLoader}). A fresh remote deploy needs APPROVED patterns for P3 correlation to have
 * anything to match against, but today those only exist AFTER the resource-heavy pattern-miner runs.
 * This loader ships a set of known-good, TRUE-cause-rooted cascade patterns already in the
 * {@code approved} lifecycle so P3 works out of the box; running the miner later refreshes/augments
 * the store as normal.
 *
 * <p><b>Why this survives a fresh topology snapshot.</b> The Correlation Engine matches an approved
 * pattern to trails by <b>structure</b> — the hostability-subset rule over {@code objectType}s
 * ({@code CompatibilityEvaluator}: a trail is compatible iff its member objectTypes are a superset of
 * the pattern's required types AND contain the root type), area-agnostic, per {@code (pattern, trail)}
 * pair — NOT by the pattern's {@code trailId}. It derives each pattern's required objectTypes from
 * {@code PatternView.sampleAlarms[].managedObjectId} prefixes ({@code "<objectType>:<id>"}). So the
 * seed's {@code trailId} is provenance-only (a synthetic {@code seed:*} reference) and every seed
 * pattern generalizes to EVERY structurally-compatible trail in whatever snapshot a fresh P1 topology
 * ingest produces. Each seed sequence element carries a sample-alarm witness of its objectType, and
 * the root type is always present, so the compatibility index accepts the seed against a fresh
 * snapshot's trails.
 *
 * <p><b>Idempotency.</b> Each seed's {@code patternId} is a deterministic UUIDv5 over its stable
 * {@code seedId}, so a restart re-derives the same id and the loader skips a row already present —
 * logging "{@code N new patterns}" exactly like Knowledge's SeedLoader.
 */
@Component
@ConditionalOnProperty(name = "pattern-manager.seed.on-startup", havingValue = "true",
        matchIfMissing = true)
public class PatternSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PatternSeedLoader.class);
    private static final String REVIEWER = "seed";
    private static final String DEFAULT_DOMAIN = "core-ip";

    private final PatternStoreService store;
    private final PatternEventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final PatternSeedProperties properties;

    public PatternSeedLoader(PatternStoreService store, PatternEventPublisher eventPublisher,
            ObjectMapper mapper, PatternSeedProperties properties) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        if (!properties.onStartup()) {
            log.info("pattern-seed-on-startup disabled; skipping pre-approved seed pattern pack");
            return;
        }
        loadPack(properties.pack());
    }

    /**
     * Load a seed pack resource through the Pattern Store's sole-writer approved path; skip patterns
     * already present (idempotent).
     *
     * @param resourcePath the classpath resource of the seed pack
     * @return the number of NEW patterns loaded (0 if the pack is absent or fully already-present)
     */
    public int loadPack(String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("pattern seed pack {} not found; skipping", resourcePath);
            return 0;
        }
        JsonNode pack;
        try (InputStream in = resource.getInputStream()) {
            pack = mapper.readTree(in);
        }
        String domain = text(pack, "domain", DEFAULT_DOMAIN);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int loaded = 0;
        for (JsonNode node : pack.path("patterns")) {
            String seedId = text(node, "seedId", null);
            if (seedId == null) {
                log.warn("pattern seed entry missing seedId; skipping entry");
                continue;
            }
            UUID patternId = UuidV5.from(seedId);
            if (store.patternExists(patternId)) {
                continue; // already seeded — idempotent
            }
            EnrichedPattern enriched = toEnriched(node, domain);
            PatternEntity entity = store.persistApprovedSeed(patternId, enriched, REVIEWER, now);
            if (properties.emitApprovedEvents()) {
                // A CE already running picks the seed up via patterns.approved; a cold-start CE reads
                // the approved set from the read API regardless (belt-and-braces).
                eventPublisher.publishApproved(entity, UUID.randomUUID().toString());
            }
            loaded++;
        }
        log.info("pattern seed pack {} loaded ({} new approved patterns for domain {})",
                resourcePath, loaded, domain);
        return loaded;
    }

    /** Map one seed JSON entry into the same {@link EnrichedPattern} a mined+enriched pattern yields. */
    private EnrichedPattern toEnriched(JsonNode node, String domain) {
        List<String> sequence = readStringList(node.path("sequence"));
        String rootCauseAlarmType = text(node, "rootCauseAlarmType", null);
        if (rootCauseAlarmType == null || sequence.isEmpty()) {
            throw new IllegalStateException(
                    "seed pattern " + text(node, "seedId", "?")
                            + " must declare a non-empty sequence and a rootCauseAlarmType");
        }
        double support = node.path("support").asDouble(0.0);
        double confidence = node.path("confidence").asDouble(0.0);
        double lift = node.path("lift").asDouble(0.0);
        Map<String, Object> timing = readMap(node.path("timing"));

        JsonNode sw = node.path("sessionWindow");
        long windowMs = sw.path("windowMs").asLong(0L);
        if (windowMs <= 0) {
            throw new IllegalStateException("seed pattern " + text(node, "seedId", "?")
                    + " must declare sessionWindow.windowMs > 0");
        }
        WindowType windowType = "fixed".equals(sw.path("type").asText("gap-based"))
                ? WindowType.FIXED : WindowType.GAP_BASED;
        DerivedSessionWindow sessionWindow = new DerivedSessionWindow(windowMs, windowType);

        String reconcileStatus = text(node, "reconcileStatus", "confirmed");
        String codebookMatchId = text(node, "codebookMatchId", null);
        List<SampleAlarm> sampleAlarms = SampleAlarm.parse(node);
        // Provenance trailId is a synthetic seed reference — NEVER a runtime matching key (see class
        // doc). CE generalizes the pattern by structure to all compatible trails in the live snapshot.
        String trailId = "seed:" + text(node, "seedId", "unknown");

        return new EnrichedPattern(
                trailId,
                sequence,
                rootCauseAlarmType,
                support,
                confidence,
                lift,
                timing,
                sessionWindow,
                codebookMatchId,
                reconcileStatus,
                true,                 // authored: connected dependency path
                null,                 // no structural-validation reason (validated)
                Math.max(1, sampleAlarms.size()),
                List.of(),            // no supporting instances for a seed
                sampleAlarms,
                domain,
                null,                 // snapshotId: seed is snapshot-agnostic (generalized by structure)
                null,                 // codebookVersion
                null,                 // anchorScenarioId (unexplained-style identity; not consolidated)
                null);                // sourceWindowId
    }

    private List<String> readStringList(JsonNode arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (!n.isNull()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    private Map<String, Object> readMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private static String text(JsonNode node, String field, String dflt) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return dflt;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? dflt : s;
    }
}
