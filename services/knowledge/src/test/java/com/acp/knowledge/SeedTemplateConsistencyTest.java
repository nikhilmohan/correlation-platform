package com.acp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed-consistency guard for the Core IP propagation cascade (#262 anti-regression).
 *
 * <p>This is a pure JSON-parsing unit test (no Spring context, no database) so it runs in every
 * {@code ./gradlew build} regardless of Docker availability. It asserts that the authored Knowledge
 * seed forms a CONNECTED cascade: every {@code propagationTemplate.trigger.alarmType} is a token
 * that is actually <em>emitted</em> somewhere in the domain — either by a {@code faultOriginType
 * .originAlarmType} (a cascade root) or by some other {@code propagationTemplate.effect.alarmType}
 * (a downstream link). A trigger token that nothing emits is an "orphan": the template can never
 * fire, silently halting the cascade.
 *
 * <p>The bug fixed in #262 was exactly such a silent data typo — the FiberSpan {@code RIDES_ON}
 * template triggered on {@code FiberFault}, a token no origin emits and no effect produces, so the
 * fiber-cut cascade (FiberCut(FiberSpan) =&gt; LinkDown(IPLink) =&gt; LSPDown =&gt; ReachabilityLoss)
 * never started and FiberSpan/IPLink/Node scenarios collapsed to a single root symptom. On the
 * pre-fix seed this test FAILS (FiberFault is an orphan trigger); after aligning the triggers to the
 * emitted origin tokens it PASSES.
 *
 * <p>Maps to the Knowledge seed-integrity expectation that the Core IP pack loads as a coherent,
 * dogfood-validated domain; recorded as a new consistency guard (no prior AC asserted intra-pack
 * cascade reachability).
 */
class SeedTemplateConsistencyTest {

    private static final String SEED_RESOURCE = "seed/core-ip.json";
    private static JsonNode records;

    @BeforeAll
    static void loadSeed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in =
                SeedTemplateConsistencyTest.class.getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(in, "core-ip seed must be on the classpath at " + SEED_RESOURCE);
            records = mapper.readTree(in).get("records");
        }
        assertNotNull(records, "seed must contain a 'records' array");
        assertTrue(records.isArray() && records.size() > 0, "seed records must be a non-empty array");
    }

    /** The complete set of alarm tokens that are EMITTED in the domain: every origin token plus every
     * template effect token. Any trigger must be a member of this set to be reachable. */
    private static Set<String> emittedTokens() {
        Set<String> emitted = new LinkedHashSet<>();
        for (JsonNode rec : records) {
            String type = rec.path("recordType").asText();
            JsonNode payload = rec.path("payload");
            if ("faultOriginType".equals(type)) {
                emitted.add(payload.path("originAlarmType").asText());
            } else if ("propagationTemplate".equals(type)) {
                emitted.add(payload.path("effect").path("alarmType").asText());
            }
        }
        return emitted;
    }

    @Test
    void everyTemplateTriggerIsEmittedSomewhere_noOrphanTriggerHaltsTheCascade() {
        Set<String> emitted = emittedTokens();

        // Collect orphan triggers: trigger tokens that no origin emits and no effect produces.
        Set<String> orphanTriggers = new TreeSet<>();
        int templateCount = 0;
        for (JsonNode rec : records) {
            if (!"propagationTemplate".equals(rec.path("recordType").asText())) {
                continue;
            }
            templateCount++;
            String recordId = rec.path("recordId").asText();
            String trigger = rec.path("payload").path("trigger").path("alarmType").asText();
            assertFalse(trigger.isEmpty(), "template " + recordId + " has no trigger.alarmType");
            if (!emitted.contains(trigger)) {
                orphanTriggers.add(trigger + " (in " + recordId + ")");
            }
        }

        assertTrue(templateCount > 0, "seed must declare propagation templates");
        assertTrue(orphanTriggers.isEmpty(),
                "every propagationTemplate.trigger.alarmType must be emitted by some "
                        + "faultOriginType.originAlarmType or another template's effect.alarmType so the "
                        + "cascade is reachable; orphan triggers (nothing emits them) found: " + orphanTriggers);
    }

    @Test
    void fiberSpanRidesOnTemplate_triggersOnEmittedFiberCutToken() {
        // The headline #262 fix: the FiberSpan-origin RIDES_ON => IPLink/LinkDown template must trigger
        // on FiberCut (the FiberSpan faultOriginType origin token), not the orphan FiberFault.
        JsonNode ridesOn = templateById("core-ip/propagationTemplate/RIDES_ON");
        assertNotNull(ridesOn, "the FiberSpan RIDES_ON => IPLink/LinkDown template must exist");

        JsonNode payload = ridesOn.path("payload");
        assertEquals("FiberSpan", payload.path("trigger").path("objectType").asText());
        assertEquals("FiberCut", payload.path("trigger").path("alarmType").asText(),
                "FiberSpan RIDES_ON must trigger on the emitted FiberCut origin token (#262)");
        assertEquals("IPLink", payload.path("effect").path("objectType").asText());
        assertEquals("LinkDown", payload.path("effect").path("alarmType").asText());

        // And the emitted token must actually be a FiberSpan faultOriginType origin token.
        assertEquals("FiberCut", originTokenFor("FiberSpan"),
                "FiberSpan faultOriginType must emit FiberCut, the token the RIDES_ON template fires on");
    }

    @Test
    void fiberCutCascadeIsConnected_fiberSpanReachesVpnService() {
        // Beyond the single template: walk the trigger->effect graph from the FiberSpan origin token and
        // assert the full fiber-cut tail (LinkDown -> LSPDown -> ReachabilityLoss) is reachable. This is
        // the AC-1 cascade the orphan trigger silently broke.
        Set<String> reachable = new HashSet<>();
        reachable.add(originTokenFor("FiberSpan")); // FiberCut

        // Fixed-point closure over template trigger->effect edges.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JsonNode rec : records) {
                if (!"propagationTemplate".equals(rec.path("recordType").asText())) {
                    continue;
                }
                JsonNode p = rec.path("payload");
                String trigger = p.path("trigger").path("alarmType").asText();
                String effect = p.path("effect").path("alarmType").asText();
                if (reachable.contains(trigger) && reachable.add(effect)) {
                    grew = true;
                }
            }
        }

        assertTrue(reachable.contains("LinkDown"),
                "fiber-cut cascade must reach LinkDown(IPLink); reachable=" + new TreeSet<>(reachable));
        assertTrue(reachable.contains("LSPDown"),
                "fiber-cut cascade must reach LSPDown(LSP); reachable=" + new TreeSet<>(reachable));
        assertTrue(reachable.contains("ReachabilityLoss"),
                "fiber-cut cascade must reach ReachabilityLoss(VPNService); reachable=" + new TreeSet<>(reachable));
    }

    private static JsonNode templateById(String recordId) {
        for (JsonNode rec : records) {
            if ("propagationTemplate".equals(rec.path("recordType").asText())
                    && recordId.equals(rec.path("recordId").asText())) {
                return rec;
            }
        }
        return null;
    }

    private static String originTokenFor(String objectType) {
        for (JsonNode rec : records) {
            if ("faultOriginType".equals(rec.path("recordType").asText())
                    && objectType.equals(rec.path("payload").path("objectType").asText())) {
                return rec.path("payload").path("originAlarmType").asText();
            }
        }
        return null;
    }
}
