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

    /**
     * BACKWARD invariant (#262 tail anti-regression). The forward guard above proves no template
     * trigger is an orphan; it does NOT catch the symmetric defect that let IPLink and Node slip
     * through: a faultOriginType whose origin token matches no template trigger, so the origin can
     * never start a cascade and its scenarios collapse to a single (root-only) symptom.
     *
     * <p>For EVERY {@code faultOriginType}, its {@code originAlarmType} must equal the
     * {@code trigger.alarmType} of at least one {@code propagationTemplate} whose
     * {@code trigger.objectType} equals that origin's {@code objectType} (so the origin can cascade)
     * — UNLESS the origin object type is in the small, explicitly-declared LEAF set below, where a
     * terminal (no-downstream-cascade) origin is the intended modeling.
     *
     * <p>On the pre-fix seed this FAILS: IPLink emits {@code IPLinkDown} (templates trigger on
     * {@code IPLink/LinkDown}) and Node emits {@code LOS} (no template triggers on {@code Node/*}),
     * neither of which is a declared leaf. After aligning IPLink's origin token to {@code LinkDown}
     * and declaring Node a {@code NodeDown} leaf, it PASSES.
     */
    private static final Set<String> LEAF_ORIGIN_OBJECT_TYPES = Set.of(
            // Node: node-level failure (power loss / reboot) is a documented leaf origin in the MVP —
            // no downstream cascade chain is authored for it (a Node->hosted-LineCard/Port cascade
            // would be a deliberate modeling decision, escalated to the human, not authored here).
            "Node");

    @Test
    void everyFaultOriginTokenTriggersSomeTemplateOnItsObjectType_orIsADeclaredLeaf() {
        Set<String> nonCascadingOrigins = new TreeSet<>();
        int originCount = 0;

        for (JsonNode rec : records) {
            if (!"faultOriginType".equals(rec.path("recordType").asText())) {
                continue;
            }
            originCount++;
            JsonNode payload = rec.path("payload");
            String objectType = payload.path("objectType").asText();
            String originToken = payload.path("originAlarmType").asText();
            String recordId = rec.path("recordId").asText();
            assertFalse(objectType.isEmpty(), "faultOriginType " + recordId + " has no objectType");
            assertFalse(originToken.isEmpty(),
                    "faultOriginType " + recordId + " has no originAlarmType");

            if (LEAF_ORIGIN_OBJECT_TYPES.contains(objectType)) {
                // Declared leaf: it is intentionally terminal and need not match any template trigger.
                continue;
            }
            if (!someTemplateTriggersOn(objectType, originToken)) {
                nonCascadingOrigins.add(
                        objectType + "(" + originToken + ") in " + recordId);
            }
        }

        assertTrue(originCount > 0, "seed must declare fault-origin types");
        assertTrue(nonCascadingOrigins.isEmpty(),
                "every faultOriginType.originAlarmType must equal the trigger.alarmType of at least one "
                        + "propagationTemplate whose trigger.objectType == the origin's objectType (so the "
                        + "origin can cascade), unless the origin objectType is a declared leaf "
                        + LEAF_ORIGIN_OBJECT_TYPES + "; non-cascading origins (origin token matches no "
                        + "template trigger on its object type) found: " + nonCascadingOrigins);
    }

    @Test
    void iplinkOriginEmitsCanonicalLinkDownAndCascadesToVpnService() {
        // #262 tail: IPLink as an ORIGIN must emit LinkDown (the canonical IPLink-down token the
        // TRAVERSES/MEMBER_OF templates trigger on), not the dead-end IPLinkDown, so the IPLink-origin
        // cascade (LinkDown -> LSPDown -> ReachabilityLoss) fires just like the FiberSpan-reached case.
        assertEquals("LinkDown", originTokenFor("IPLink"),
                "IPLink faultOriginType must emit the canonical LinkDown token so the IPLink origin cascades");
        assertTrue(someTemplateTriggersOn("IPLink", "LinkDown"),
                "a propagationTemplate must trigger on IPLink/LinkDown so the IPLink origin can cascade");

        Set<String> reachable = new HashSet<>();
        reachable.add(originTokenFor("IPLink")); // LinkDown
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JsonNode rec : records) {
                if (!"propagationTemplate".equals(rec.path("recordType").asText())) {
                    continue;
                }
                JsonNode p = rec.path("payload");
                if (reachable.contains(p.path("trigger").path("alarmType").asText())
                        && reachable.add(p.path("effect").path("alarmType").asText())) {
                    grew = true;
                }
            }
        }
        assertTrue(reachable.contains("LSPDown"),
                "IPLink origin must cascade to LSPDown(LSP); reachable=" + new TreeSet<>(reachable));
        assertTrue(reachable.contains("ReachabilityLoss"),
                "IPLink origin must cascade to ReachabilityLoss(VPNService); reachable="
                        + new TreeSet<>(reachable));
    }

    @Test
    void nodeOriginIsADeclaredNodeDownLeaf() {
        // #262 tail: Node is a documented leaf origin — it emits NodeDown and has no downstream
        // cascade template (a Node->hosted-equipment cascade would be a separate modeling decision).
        assertEquals("NodeDown", originTokenFor("Node"),
                "Node faultOriginType must emit NodeDown (leaf origin token), not the optical LOS token");
        assertTrue(LEAF_ORIGIN_OBJECT_TYPES.contains("Node"),
                "Node must be declared a leaf origin in the backward-invariant guard");
        assertFalse(someTemplateTriggersOn("Node", "NodeDown"),
                "Node is a leaf: no template should trigger on it (else it would not be a leaf)");
    }

    private static boolean someTemplateTriggersOn(String objectType, String alarmType) {
        for (JsonNode rec : records) {
            if (!"propagationTemplate".equals(rec.path("recordType").asText())) {
                continue;
            }
            JsonNode trigger = rec.path("payload").path("trigger");
            if (objectType.equals(trigger.path("objectType").asText())
                    && alarmType.equals(trigger.path("alarmType").asText())) {
                return true;
            }
        }
        return false;
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
