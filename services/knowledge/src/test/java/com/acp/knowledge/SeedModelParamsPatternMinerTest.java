package com.acp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed-completeness guard for the {@code core-ip/modelParams/pattern-miner} record (deploy-blocker
 * anti-regression).
 *
 * <p>The pattern-miner reads its Stage-2 domain-anchoring params and the XAI sample cap
 * <em>fail-fast, with no code default</em> from this Knowledge model-params record (pattern-miner
 * spec AC-7 / AC-17 / AC-26). If the authored seed omits any of the required keys the miner exits 1
 * on a clean deploy ({@code KnowledgeError: ... missing required key 'anchoring.matchConfidenceThreshold'})
 * and P2 mining never runs, so P3 correlation has nothing to match.
 *
 * <p>This is a pure JSON-parsing unit test (no Spring context, no database) so it runs in every
 * {@code ./gradlew build} regardless of Docker availability. It asserts the four fail-fast keys the
 * miner {@code _require(...)}s are present with in-range values, and that the optional structural
 * keys (scorer method / tie-break / grouping keys) are authored for completeness. Knowledge is the
 * sole owner of authored params — this record is the only place these are declared.
 */
class SeedModelParamsPatternMinerTest {

    private static final String SEED_RESOURCE = "seed/core-ip.json";
    private static final String RECORD_ID = "core-ip/modelParams/pattern-miner";

    private static Map<String, JsonNode> params;

    @BeforeAll
    static void loadPatternMinerParams() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode records;
        try (InputStream in =
                SeedModelParamsPatternMinerTest.class.getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(in, "core-ip seed must be on the classpath at " + SEED_RESOURCE);
            records = mapper.readTree(in).get("records");
        }
        assertNotNull(records, "seed must contain a 'records' array");

        JsonNode record = null;
        for (JsonNode rec : records) {
            if ("modelParams".equals(rec.path("recordType").asText())
                    && RECORD_ID.equals(rec.path("recordId").asText())) {
                record = rec;
                break;
            }
        }
        assertNotNull(record, "seed must declare the " + RECORD_ID + " model-params record");

        params = new LinkedHashMap<>();
        JsonNode paramArray = record.path("payload").path("params");
        assertTrue(paramArray.isArray() && paramArray.size() > 0,
                "pattern-miner params must be a non-empty array");
        for (JsonNode p : paramArray) {
            params.put(p.path("key").asText(), p);
        }
    }

    private static JsonNode requireKey(String key) {
        JsonNode p = params.get(key);
        assertNotNull(p, "pattern-miner model-params record must author the fail-fast key '" + key
                + "' (the miner _require()s it with no code default)");
        assertTrue(p.path("value").isValueNode() || p.path("value").isContainerNode(),
                "'" + key + "' must have a value");
        return p;
    }

    /** The four keys the miner reads fail-fast (no code default): missing any → miner exits 1. */
    @Test
    void requiredAnchoringAndSampleKeys_arePresent_withInRangeValues() {
        JsonNode threshold = requireKey("anchoring.matchConfidenceThreshold");
        assertEquals("number", threshold.path("type").asText());
        double t = threshold.path("value").asDouble();
        assertTrue(t >= 0.0 && t <= 1.0,
                "anchoring.matchConfidenceThreshold must be a confidence in [0,1]; was " + t);

        JsonNode wOrder = requireKey("anchoring.weights.order");
        assertEquals("number", wOrder.path("type").asText());
        double wo = wOrder.path("value").asDouble();
        assertTrue(wo >= 0.0 && wo <= 1.0, "anchoring.weights.order must be in [0,1]; was " + wo);

        JsonNode wJaccard = requireKey("anchoring.weights.jaccard");
        assertEquals("number", wJaccard.path("type").asText());
        double wj = wJaccard.path("value").asDouble();
        assertTrue(wj >= 0.0 && wj <= 1.0, "anchoring.weights.jaccard must be in [0,1]; was " + wj);

        // Scorer weights are authored to sum to 1.0 (a weighted blend), per the miner's fixture.
        assertEquals(1.0, wo + wj, 1e-9,
                "anchoring scorer weights (order + jaccard) must sum to 1.0; was " + (wo + wj));

        JsonNode maxAlarms = requireKey("sample.maxAlarms");
        assertEquals("integer", maxAlarms.path("type").asText());
        assertTrue(maxAlarms.path("value").isIntegralNumber(),
                "sample.maxAlarms must be an integer");
        assertTrue(maxAlarms.path("value").asInt() >= 1,
                "sample.maxAlarms (the XAI sampleAlarms[] cap K) must be a positive cap");
    }

    /** Optional structural tokens — authored for completeness so the miner never falls back. */
    @Test
    void optionalStructuralAnchoringKeys_areAuthored() {
        JsonNode scoringMethod = requireKey("anchoring.scoringMethod");
        assertEquals("string", scoringMethod.path("type").asText());
        assertEquals("ordered_subsequence_jaccard", scoringMethod.path("value").asText());

        JsonNode tieBreak = requireKey("anchoring.tieBreak");
        assertEquals("string", tieBreak.path("type").asText());
        assertEquals("chain_length_then_scenario_id", tieBreak.path("value").asText());

        JsonNode groupingKeys = requireKey("anchoring.groupingKeys");
        JsonNode value = groupingKeys.path("value");
        assertTrue(value.isArray() && value.size() > 0,
                "anchoring.groupingKeys must be a non-empty list of grouping keys");
        assertEquals("scenarioId", value.get(0).asText(),
                "the default anchor grouping is one group per fault-origin scenario (scenarioId)");
    }
}
