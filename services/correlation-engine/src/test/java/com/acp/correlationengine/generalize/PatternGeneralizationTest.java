package com.acp.correlationengine.generalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.acp.correlationengine.support.EngineHarness;
import com.acp.correlationengine.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pattern generalization acceptance criteria AC31–AC40, AC43, AC45 (the correlation-core behaviours),
 * driven Kafka-free through {@link EngineHarness} + a fake Trail Builder. Each pattern's required
 * object types are derived from its {@code sampleAlarms} object-type witnesses (see {@link Fixtures});
 * trails declare their member object types on the fake Trail Builder. The discovery trail is auto-hosted
 * (AC33), other trails are declared explicitly.
 */
class PatternGeneralizationTest {

    private static final long T0 = Fixtures.T0;

    /**
     * A pattern whose sequence alarm types map onto three object types A, B, C (root = A). Discovery
     * trail is {@code discoveryTrailId}. Sample-alarm witnesses use typed managedObjectIds so the
     * required object types resolve from the prefix (no hard-coded mapping).
     */
    private static PatternRef abcPattern(String patternId, String discoveryTrailId) {
        return new PatternRef(patternId, discoveryTrailId,
                List.of("aType", "bType", "cType"), "aType", 0.9, 60_000, WindowType.GAP_BASED,
                Map.of("aType", "A", "bType", "B", "cType", "C"));
    }

    private static void fullCascade(EngineHarness h, String trail, String suffix, long base) {
        h.feed(Fixtures.alarm("a-" + suffix, "aType", base), trail, base);
        h.feed(Fixtures.alarm("b-" + suffix, "bType", base + 1), trail, base + 1);
        h.feed(Fixtures.alarm("c-" + suffix, "cType", base + 2), trail, base + 2);
    }

    /** AC31 — a generalized pattern matches on a non-discovery, structurally compatible trail. */
    @Test
    void ac31_generalizedPatternMatchesOnNonDiscoveryTrail() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T_other", List.of("A", "B", "C")); // compatible, non-discovery
        h.addPattern(p);

        fullCascade(h, "T_other", "o", T0);

        assertThat(h.results.emitted).hasSize(1);
        Incident inc = h.results.emitted.get(0);
        assertThat(inc.trailId()).isEqualTo("T_other");     // matched trail (AC31/AC43)
        assertThat(inc.matchedPatternId()).isEqualTo("P");
        assertThat(h.engine.hasInstance("T_disc", "P")).isFalse(); // no discovery-trail instance
    }

    /** AC32 — a trail missing a required object type is not a candidate. */
    @Test
    void ac32_incompatibleTrailMissingRequiredTypeIsNotCandidate() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T_incompat", List.of("A", "B")); // missing C
        h.addPattern(p);

        fullCascade(h, "T_incompat", "i", T0);

        assertThat(h.engine.hasInstance("T_incompat", "P")).isFalse();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_incompat")).isEmpty();
        assertThat(h.results.emitted).isEmpty();
    }

    /** AC33 — the discovery trail remains compatible (backward compatibility). */
    @Test
    void ac33_discoveryTrailRemainsCompatible() {
        EngineHarness h = new EngineHarness();
        h.addPattern(abcPattern("P", "T_disc")); // discovery trail auto-hosted

        fullCascade(h, "T_disc", "d", T0);

        assertThat(h.results.emitted).hasSize(1);
        assertThat(h.results.emitted.get(0).trailId()).isEqualTo("T_disc");
    }

    /** AC34 — the same pattern drives two simultaneous independent instances on two compatible trails. */
    @Test
    void ac34_samePatternTwoTrailsTwoDisjointIncidents() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T1", List.of("A", "B", "C"));
        h.declareTrail("T2", List.of("A", "B", "C"));
        h.addPattern(p);

        fullCascade(h, "T1", "1", T0);
        fullCascade(h, "T2", "2", T0);

        assertThat(h.results.emitted).hasSize(2);
        Incident i1 = h.results.emitted.stream().filter(i -> i.trailId().equals("T1"))
                .findFirst().orElseThrow();
        Incident i2 = h.results.emitted.stream().filter(i -> i.trailId().equals("T2"))
                .findFirst().orElseThrow();
        assertThat(i1.childAlarmIds()).doesNotContainAnyElementsOf(i2.childAlarmIds());
        assertThat(i1.rootCauseAlarmId()).isNotEqualTo(i2.rootCauseAlarmId());
    }

    /** AC35 — the instance key is the matched trail; the record carries matched + discovery trails. */
    @Test
    void ac35_instanceKeyIsMatchedTrail_recordCarriesBoth() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T_match", List.of("A", "B", "C"));
        h.addPattern(p);

        fullCascade(h, "T_match", "m", T0);

        Incident inc = h.results.emitted.get(0);
        assertThat(inc.trailId()).isEqualTo("T_match");         // matchedTrailId
        assertThat(inc.discoveryTrailId()).isEqualTo("T_disc"); // provenance
        assertThat(h.incidents.findById(inc.incidentId()).orElseThrow().discoveryTrailId())
                .isEqualTo("T_disc");
    }

    /** AC36 — dispatch is driven by the compatibility index, not a discovery-trail registry. */
    @Test
    void ac36_dispatchUsesCompatibilityIndexNotDiscoveryRegistry() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T_disc", List.of("A", "B", "C"));  // explicit so it can later be made incompatible
        h.declareTrail("T_other", List.of("A", "B", "C"));
        h.addPattern(p);

        // Initially both compatible.
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_disc")).hasSize(1);
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other")).hasSize(1);

        // Make the discovery trail incompatible (Trail Builder now reports it missing C) and rebuild.
        h.declareTrail("T_disc", List.of("A", "B"));
        h.rebuild();

        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_disc")).isEmpty();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other")).hasSize(1);

        fullCascade(h, "T_disc", "d", T0);
        assertThat(h.engine.hasInstance("T_disc", "P")).isFalse(); // no longer dispatched to P
        fullCascade(h, "T_other", "o", T0 + 100);
        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T_other");
    }

    /** AC37 — the index is rebuilt on a new snapshot (trails.built) — removed trail drops, new trail appears. */
    @Test
    void ac37_indexRebuiltOnNewSnapshot() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T1", List.of("A", "B", "C"));
        h.declareTrail("T2", List.of("A", "B", "C"));
        h.addPattern(p);
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T2")).hasSize(1);

        // New snapshot: T2 gone (removed), T3 added (compatible).
        h.trailBuilder.removeTrail("T2");
        h.declareTrail("T3", List.of("A", "B", "C"));
        h.rebuild(); // simulates the trails.built-triggered rebuildAll

        assertThat(h.compatibilityIndex.patternsCompatibleWith("T2")).isEmpty();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T3")).hasSize(1);

        fullCascade(h, "T2", "2", T0);
        assertThat(h.engine.hasInstance("T2", "P")).isFalse();
        fullCascade(h, "T3", "3", T0 + 100);
        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T3");
    }

    /** AC38 — the index is updated on pattern approval (rebuildForPattern), before the pattern is matchable. */
    @Test
    void ac38_indexUpdatedOnPatternApproval() {
        EngineHarness h = new EngineHarness();
        // Trail catalog exists first; no approved patterns yet.
        h.declareTrail("T1", List.of("A", "B", "C")); // compatible with P_new
        h.declareTrail("T2", List.of("A", "B"));       // incompatible with P_new (missing C)
        h.rebuild();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T1")).isEmpty();

        // Approve P_new: place it via rebuildForPattern (the patterns.approved path).
        PatternRef pNew = abcPattern("P_new", "T_disc_new");
        h.patternStore.upsert(pNew);
        h.compatibilityIndex.rebuildForPattern("P_new");

        assertThat(h.compatibilityIndex.patternsCompatibleWith("T1")).hasSize(1);
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T2")).isEmpty();

        fullCascade(h, "T1", "1", T0);
        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T1");
    }

    /**
     * AC39 — object-type affinity drives compatibility with NO hard-coded mapping: the resolver derives
     * required object types from the pattern's non-default sample-alarm managedObjectId prefixes.
     */
    @Test
    void ac39_affinityDrivenCompatibility_noHardCodedMapping() {
        EngineHarness h = new EngineHarness();
        // Non-default object types with no Core-IP meaning; witnessed via managedObjectId prefixes.
        PatternRef p = new PatternRef("P", "T_disc",
                List.of("wobble", "flicker"), "wobble", 0.9, 60_000, WindowType.GAP_BASED,
                Map.of("wobble", "WidgetX", "flicker", "GadgetY"));
        h.declareTrail("T_match", List.of("WidgetX", "GadgetY")); // hosts both custom types
        h.declareTrail("T_nomatch", List.of("WidgetX"));           // missing GadgetY
        h.addPattern(p);

        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_match")).hasSize(1);
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_nomatch")).isEmpty();

        h.feed(Fixtures.alarm("w1", "wobble", T0), "T_match", T0);
        h.feed(Fixtures.alarm("f1", "flicker", T0 + 1), "T_match", T0 + 1);
        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T_match");
    }

    /**
     * AC40 — per-alarm dispatch is a bounded index lookup: with a large index (many patterns x many
     * trails), an alarm on a single trail only sees that trail's compatible patterns — not the whole
     * (pattern x trail) space.
     */
    @Test
    void ac40_perAlarmDispatchIsBoundedIndexLookup() {
        EngineHarness h = new EngineHarness();
        // 50 patterns, each compatible with its own 20 trails; plus one target trail with exactly 1 pattern.
        for (int t = 0; t < 20; t++) {
            h.declareTrail("bulkTrail-" + t, List.of("A", "B", "C"));
        }
        for (int pIdx = 0; pIdx < 50; pIdx++) {
            h.patternStore.upsert(abcPattern("bulkP-" + pIdx, "disc-" + pIdx));
        }
        h.declareTrail("targetTrail", List.of("A")); // hosts only patterns requiring exactly {A}
        PatternRef onlyA = new PatternRef("onlyA", "discA", List.of("aType"), "aType", 0.9,
                60_000, WindowType.GAP_BASED, Map.of("aType", "A"));
        h.patternStore.upsert(onlyA);
        h.rebuild();

        // Bulk patterns require {A,B,C}; targetTrail only hosts {A}. So targetTrail has exactly onlyA.
        List<PatternRef> compatible = h.compatibilityIndex.patternsCompatibleWith("targetTrail");
        assertThat(compatible).extracting(PatternRef::patternId).containsExactly("onlyA");
        // The bulk index is far larger than the per-trail lookup result — the lookup is bounded.
        assertThat(compatible.size()).isLessThan(50);
    }

    /** AC43 — CorrelationResultEvent.trailId is the matched trail, never the discovery trail. */
    @Test
    void ac43_resultTrailIdIsMatchedTrail() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T_match", List.of("A", "B", "C"));
        h.addPattern(p);

        fullCascade(h, "T_match", "m", T0);

        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T_match");
        assertThat(h.results.emitted.get(0).trailId()).isNotEqualTo("T_disc");
    }

    /** AC45 — simultaneous instances of one pattern on two trails do not interfere (expiry + fire). */
    @Test
    void ac45_simultaneousInstancesDoNotInterfere() {
        EngineHarness h = new EngineHarness();
        PatternRef p = abcPattern("P", "T_disc");
        h.declareTrail("T1", List.of("A", "B", "C"));
        h.declareTrail("T2", List.of("A", "B", "C"));
        h.addPattern(p);

        // T1: only the opening alarm arrives (will expire, partial).
        h.feed(Fixtures.alarm("t1-a", "aType", T0), "T1", T0);
        // T2: full cascade -> fires an incident for T2.
        fullCascade(h, "T2", "2", T0);

        assertThat(h.results.emitted).extracting(Incident::trailId).containsExactly("T2");

        // Advance past the window so T1's partial instance expires and reverts only its own alarm.
        h.tick(T0 + 120_000);
        assertThat(h.statuses.alarmIdsWith("reverted-open")).containsExactly("t1-a");
        // T2's alarms were correlated (not reverted).
        assertThat(h.statuses.alarmIdsWith("reverted-open")).doesNotContain("a-2", "b-2", "c-2");
    }
}
